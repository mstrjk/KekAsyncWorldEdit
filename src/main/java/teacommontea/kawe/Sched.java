package teacommontea.kawe;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import teacommontea.kawe.engine.RegionQueue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class Sched {

    private static final class CommandThread extends Thread {
        CommandThread(Runnable task, String name) {
            super(task, name);
            setDaemon(true);
            setPriority(Thread.NORM_PRIORITY - 1);
        }
    }

    private static final class ParallelThread extends ForkJoinWorkerThread {
        ParallelThread(ForkJoinPool pool, String name) {
            super(null, pool, true);
            setName(name);
            setDaemon(true);
        }
    }

    private static Plugin plugin;
    private static boolean folia;
    private static volatile MethodHandle chunkTask;
    private static ThreadPoolExecutor compute;
    private static ForkJoinPool parallel;
    private static int threads;
    private static volatile boolean running;
    private static final Set<CompletableFuture<?>> PENDING = ConcurrentHashMap.newKeySet();

    private Sched() {
    }

    static void start(Plugin owner, int parallelThreads, int commandThreads) {
        plugin = owner;
        folia = detectFolia();
        chunkTask = folia ? foliaChunkTask() : null;
        threads = Math.max(1, parallelThreads);
        int commands = Math.max(1, commandThreads);
        AtomicInteger commandCounter = new AtomicInteger();
        compute = new ThreadPoolExecutor(commands, commands, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new CommandThread(r, "KAWE-Command-" + commandCounter.incrementAndGet()));
        AtomicInteger parallelCounter = new AtomicInteger();
        parallel = new ForkJoinPool(threads, pool -> new ParallelThread(pool, "KAWE-Parallel-" + parallelCounter.incrementAndGet()),
                (thread, error) -> plugin.getLogger().log(Level.SEVERE, "KAWE parallel task failed", error), false);
        running = true;
    }

    static void stop() {
        running = false;
        RegionQueue.shutdownAll();
        if (compute != null) {
            compute.shutdownNow();
        }
        if (parallel != null) {
            parallel.shutdownNow();
        }
        IllegalStateException stopped = new IllegalStateException("KAWE is shutting down");
        for (CompletableFuture<?> future : List.copyOf(PENDING)) {
            future.completeExceptionally(stopped);
        }
        PENDING.clear();
        awaitQuietly(compute);
        awaitQuietly(parallel);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
    }

    private static void awaitQuietly(ExecutorService pool) {
        if (pool == null) {
            return;
        }
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("KAWE pool did not stop within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean running() {
        return running;
    }

    private static MethodHandle foliaChunkTask() {
        try {
            Class<?> server = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Object instance = server.getMethod("getInstance").invoke(null);
            Object queue = server.getField("taskQueue").get(instance);
            Method method = queue.getClass().getMethod("queueChunkTask", ServerLevel.class, int.class, int.class, Runnable.class);
            return MethodHandles.publicLookup().unreflect(method).bindTo(queue);
        } catch (ReflectiveOperationException | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Folia chunk task queue not found, region work waits for region ticks", error);
            return null;
        }
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static int regionChunkShift() {
        try {
            Object value = Class.forName("io.papermc.paper.threadedregions.TickRegions").getMethod("getRegionChunkShift").invoke(null);
            return (Integer) value;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return 4;
        }
    }

    public static Plugin plugin() {
        return plugin;
    }

    public static boolean folia() {
        return folia;
    }

    public static int threads() {
        return threads;
    }

    public static ExecutorService compute() {
        return compute;
    }

    public static ForkJoinPool parallel() {
        return parallel;
    }

    public static boolean owns(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static void region(World world, int chunkX, int chunkZ, Runnable task) {
        if (!running) {
            throw new IllegalStateException("KAWE is shutting down");
        }
        if (folia) {
            MethodHandle queue = chunkTask;
            if (queue == null || !Tuning.get().foliaChunkTasks()) {
                Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, task);
                return;
            }
            Runnable guarded = () -> {
                try {
                    task.run();
                } catch (RuntimeException error) {
                    plugin.getLogger().log(Level.SEVERE, "KAWE region task failed", error);
                }
            };
            try {
                Object queued = queue.invoke(((CraftWorld) world).getHandle(), chunkX, chunkZ, guarded);
                if (queued == null) {
                    throw new IllegalStateException("Folia did not queue the chunk task");
                }
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable error) {
                throw new IllegalStateException(error);
            }
            return;
        }
        MinecraftServer.getServer().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.SEVERE, "KAWE main thread task failed", error);
            }
        });
    }

    public static void regionLater(World world, int chunkX, int chunkZ, Runnable task, long ticks) {
        if (!running) {
            throw new IllegalStateException("KAWE is shutting down");
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, t -> task.run(), Math.max(1, ticks));
    }

    public static <T> CompletableFuture<T> onRegion(World world, int chunkX, int chunkZ, Supplier<T> task) {
        if (owns(world, chunkX, chunkZ)) {
            return supplyNow(task);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            region(world, chunkX, chunkZ, () -> complete(future, task));
        } catch (RuntimeException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private static <T> CompletableFuture<T> supplyNow(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        complete(future, task);
        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> task) {
        try {
            future.complete(task.get());
        } catch (Throwable error) {
            future.completeExceptionally(error);
            if (error instanceof VirtualMachineError fatal) {
                throw fatal;
            }
        }
    }

    public static boolean entity(Entity entity, Runnable task) {
        if (!running) {
            return false;
        }
        return entity.getScheduler().run(plugin, t -> task.run(), null) != null;
    }

    public static <T> CompletableFuture<T> onEntity(Entity entity, Supplier<T> task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return supplyNow(task);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            boolean scheduled = entity.getScheduler().run(plugin, t -> complete(future, task),
                    () -> future.completeExceptionally(new IllegalStateException("Entity removed"))) != null;
            if (!scheduled) {
                future.completeExceptionally(new IllegalStateException("Entity removed"));
            }
        } catch (RuntimeException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    public static void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public static void globalLater(Runnable task, long ticks) {
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1, ticks));
    }

    public static CompletableFuture<Void> async(Runnable task) {
        if (!running) {
            return CompletableFuture.failedFuture(new IllegalStateException("KAWE is shutting down"));
        }
        try {
            CompletableFuture<Void> future = CompletableFuture.runAsync(task, compute);
            PENDING.add(future);
            future.whenComplete((value, error) -> PENDING.remove(future));
            return future;
        } catch (RejectedExecutionException error) {
            return CompletableFuture.failedFuture(new IllegalStateException("KAWE is shutting down", error));
        }
    }

    public static <T> T join(CompletableFuture<T> future) {
        if (!future.isDone() && !isWorker()) {
            throw new IllegalStateException("Blocking wait outside a KAWE worker on " + Thread.currentThread().getName());
        }
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    public static <T> List<T> joinAll(List<CompletableFuture<List<T>>> parts) {
        List<T> out = new ArrayList<>();
        for (CompletableFuture<List<T>> part : parts) {
            out.addAll(join(part));
        }
        return out;
    }

    public static boolean isWorker() {
        Thread current = Thread.currentThread();
        return current instanceof CommandThread || current instanceof ParallelThread;
    }
}
