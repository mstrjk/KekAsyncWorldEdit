package teacommontea.kawe.engine;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import teacommontea.kawe.Debug;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Tuning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class RegionQueue {

    private static final Map<World, RegionQueue> QUEUES = new ConcurrentHashMap<>();
    private static final long WINDOW_NANOS = 50_000_000L;
    private static final int MAX_LOAD_ATTEMPTS = 5;
    private static final ThreadLocal<long[]> WINDOW = ThreadLocal.withInitial(() -> new long[] {Long.MIN_VALUE, 0L});
    private static volatile long budgetNanos = 40_000_000L;
    private static volatile int maxInFlight = 8192;
    private static volatile int groupShift = -1;

    private final World world;
    private final ServerLevel level;
    private final Map<Long, Group> groups = new ConcurrentHashMap<>();
    private final AtomicInteger staging = new AtomicInteger();
    private final Semaphore inFlight = new Semaphore(maxInFlight);
    private volatile boolean closed;

    private RegionQueue(World world) {
        this.world = world;
        this.level = ((CraftWorld) world).getHandle();
    }

    public void staging(int delta) {
        staging.addAndGet(delta);
    }

    public static void linger(World world, long nanos) {
        RegionQueue queue = of(world);
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until && !queue.closed) {
            for (Group group : queue.groups.values()) {
                Item<?> next = group.items.peek();
                if (next != null && Sched.owns(world, next.chunkX, next.chunkZ)) {
                    group.scheduled.set(true);
                    queue.drain(group);
                    return;
                }
            }
            Thread.onSpinWait();
        }
    }

    public static RegionQueue of(World world) {
        return QUEUES.computeIfAbsent(world, RegionQueue::new);
    }

    public static void forget(World world) {
        RegionQueue queue = QUEUES.remove(world);
        if (queue != null) {
            queue.shutdown();
        }
    }

    public static void shutdownAll() {
        for (RegionQueue queue : QUEUES.values()) {
            queue.shutdown();
        }
        QUEUES.clear();
    }

    private void shutdown() {
        closed = true;
        for (Group group : groups.values()) {
            failAll(group, new IllegalStateException("KAWE is shutting down"));
        }
        groups.clear();
    }

    private static void failAll(Group group, Throwable error) {
        Item<?> item;
        while ((item = group.items.poll()) != null) {
            item.future.completeExceptionally(error);
        }
    }

    public static void configure(int budgetMillis, int inFlightLimit) {
        budgetNanos = Math.max(1, budgetMillis) * 1_000_000L;
        maxInFlight = Math.max(64, inFlightLimit);
    }

    private static int shift() {
        int value = groupShift;
        if (value < 0) {
            value = Sched.folia() ? Sched.regionChunkShift() : 31;
            groupShift = value;
        }
        return value;
    }

    public <T> CompletableFuture<T> submit(int chunkX, int chunkZ, Function<LevelChunk, T> task) {
        if (closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("KAWE is shutting down"));
        }
        Item<T> item = new Item<>(chunkX, chunkZ, task);
        if (!inFlight.tryAcquire()) {
            if (!Sched.isWorker()) {
                throw new IllegalStateException("KAWE region queue is full and the caller cannot wait: " + Thread.currentThread().getName());
            }
            inFlight.acquireUninterruptibly();
        }
        item.future.whenComplete((value, error) -> inFlight.release());
        try {
            enqueue(item);
        } catch (RuntimeException error) {
            item.future.completeExceptionally(error);
        }
        return item.future;
    }

    private void enqueue(Item<?> item) {
        if (closed) {
            item.future.completeExceptionally(new IllegalStateException("KAWE is shutting down"));
            return;
        }
        int shift = shift();
        long key = shift >= 31 ? 0L : groupKey(item.chunkX >> shift, item.chunkZ >> shift);
        Group group = groups.computeIfAbsent(key, k -> new Group());
        group.items.add(item);
        wake(group, item, 0);
    }

    private void wake(Group group, Item<?> anchor, long delay) {
        if (Debug.timings()) {
            Debug.time("wake delay=" + delay + " scheduled=" + group.scheduled.get() + " on " + Thread.currentThread().getName());
        }
        if (group.scheduled.compareAndSet(false, true)) {
            try {
                if (delay <= 0) {
                    Sched.region(world, anchor.chunkX, anchor.chunkZ, () -> drain(group));
                } else {
                    Sched.regionLater(world, anchor.chunkX, anchor.chunkZ, () -> drain(group), delay);
                }
            } catch (RuntimeException error) {
                group.scheduled.set(false);
                failAll(group, error);
            }
        }
    }

    private static long groupKey(int gx, int gz) {
        return ((long) gx & 0xFFFFFFFFL) | ((long) gz << 32);
    }

    private static long remainingBudget() {
        long now = System.nanoTime();
        long[] window = WINDOW.get();
        if (window[0] == Long.MIN_VALUE || now - window[0] >= WINDOW_NANOS) {
            window[0] = now;
            window[1] = 0;
        }
        long budget = budgetNanos - window[1];
        Tuning tuning = Tuning.get();
        if (Sched.folia() || !tuning.idleTime()) {
            return budget;
        }
        return Math.max(budget, MinecraftServer.getServer().getNextTickTime() - now - tuning.idleMarginMs() * 1_000_000L);
    }

    private static void spend(long nanos) {
        WINDOW.get()[1] += nanos;
    }

    private void drain(Group group) {
        long start = System.nanoTime();
        if (Debug.timings()) {
            Debug.time("drain on " + Thread.currentThread().getName() + " items=" + group.items.size());
        }
        long deadline = start;
        List<Group> touched = new ArrayList<>();
        touched.add(group);
        try {
            if (closed) {
                failAll(group, new IllegalStateException("KAWE is shutting down"));
                return;
            }
            deadline = start + remainingBudget();
            int total = 0;
            while (!closed) {
                int ran = run(group, deadline, total == 0);
                if (Sched.folia()) {
                    for (Group other : groups.values()) {
                        if (other == group || System.nanoTime() >= deadline) {
                            continue;
                        }
                        Item<?> head = other.items.peek();
                        if (head != null && Sched.owns(world, head.chunkX, head.chunkZ)) {
                            if (!touched.contains(other)) {
                                touched.add(other);
                            }
                            ran += run(other, deadline, false);
                        }
                    }
                }
                total += ran;
                Tuning tuning = Tuning.get();
                if (Sched.folia() && total > 0 && !ownedWork(group)) {
                    long linger = Math.min(deadline, System.nanoTime() + tuning.foliaLingerMs() * 1_000_000L);
                    boolean whileStaging = tuning.foliaLingerWhileStaging();
                    while (!ownedWork(group) && !closed && (System.nanoTime() < linger || whileStaging && staging.get() > 0 && System.nanoTime() < deadline)) {
                        Thread.onSpinWait();
                    }
                    if (ownedWork(group)) {
                        continue;
                    }
                }
                if (ran == 0 || !ownedWork(group) || System.nanoTime() >= deadline) {
                    break;
                }
            }
        } finally {
            spend(System.nanoTime() - start);
            group.scheduled.set(false);
            for (Group pending : touched) {
                Item<?> next = pending.items.peek();
                if (next == null || closed || pending != group && pending.scheduled.get()) {
                    continue;
                }
                if (!Sched.folia()) {
                    wake(pending, next, 0);
                } else if (System.nanoTime() >= deadline) {
                    wake(pending, next, 1);
                } else {
                    Sched.parallel().execute(() -> wake(pending, next, 0));
                }
            }
        }
    }

    private int run(Group group, long deadline, boolean first) {
        int budget = group.items.size();
        int ran = 0;
        for (int examined = 0; examined < budget && (first && ran == 0 || System.nanoTime() < deadline) && !closed; examined++) {
            Item<?> item = group.items.poll();
            if (item == null) {
                break;
            }
            if (!Sched.owns(world, item.chunkX, item.chunkZ)) {
                group.items.add(item);
                continue;
            }
            runOrLoad(item);
            ran++;
        }
        return ran;
    }

    private boolean ownedWork(Group group) {
        if (!group.items.isEmpty()) {
            return true;
        }
        if (!Sched.folia()) {
            return false;
        }
        for (Group other : groups.values()) {
            Item<?> head = other.items.peek();
            if (head != null && Sched.owns(world, head.chunkX, head.chunkZ)) {
                return true;
            }
        }
        return false;
    }

    private void runOrLoad(Item<?> item) {
        LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(item.chunkX, item.chunkZ);
        if (chunk != null) {
            item.run(chunk);
            return;
        }
        if (++item.loadAttempts > MAX_LOAD_ATTEMPTS) {
            item.future.completeExceptionally(new IllegalStateException(
                    "Chunk " + item.chunkX + "," + item.chunkZ + " unloaded again before it could be edited " + MAX_LOAD_ATTEMPTS + " times"));
            return;
        }
        world.getChunkAtAsync(item.chunkX, item.chunkZ, true).whenComplete((loaded, error) -> {
            if (error != null) {
                item.future.completeExceptionally(error);
                return;
            }
            if (closed) {
                item.future.completeExceptionally(new IllegalStateException("KAWE is shutting down"));
                return;
            }
            if (!item.ticketed && world.addPluginChunkTicket(item.chunkX, item.chunkZ, Sched.plugin())) {
                item.ticketed = true;
                item.future.whenComplete((value, failure) -> release(item));
            }
            enqueue(item);
        });
    }

    private void release(Item<?> item) {
        try {
            Sched.region(world, item.chunkX, item.chunkZ, () -> world.removePluginChunkTicket(item.chunkX, item.chunkZ, Sched.plugin()));
        } catch (RuntimeException ignored) {
            world.removePluginChunkTicket(item.chunkX, item.chunkZ, Sched.plugin());
        }
    }

    private static final class Group {
        final Queue<Item<?>> items = new ConcurrentLinkedQueue<>();
        final AtomicBoolean scheduled = new AtomicBoolean();
    }

    private static final class Item<T> {
        final int chunkX;
        final int chunkZ;
        final Function<LevelChunk, T> task;
        final CompletableFuture<T> future = new CompletableFuture<>();
        int loadAttempts;
        boolean ticketed;

        Item(int chunkX, int chunkZ, Function<LevelChunk, T> task) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.task = task;
        }

        void run(LevelChunk chunk) {
            try {
                future.complete(task.apply(chunk));
            } catch (Throwable error) {
                future.completeExceptionally(error);
                if (error instanceof VirtualMachineError fatal) {
                    throw fatal;
                }
            }
        }
    }
}
