package teacommontea.kawe.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.AABB;
import teacommontea.kawe.Debug;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Tuning;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.session.Restrictions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class Edit implements Extent {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final KWorld world;
    private final Map<Long, CompletableFuture<ChunkSnap>> snaps = new ConcurrentHashMap<>();
    private final Map<Long, ChunkChanges> changes = new ConcurrentHashMap<>();
    private final AtomicLong changed = new AtomicLong();
    private final LongAdder unlimited = new LongAdder();
    private final long limit;
    private final ThreadLocal<Cache> cache = ThreadLocal.withInitial(Cache::new);
    private Mask mask;
    private Mask sourceMask;
    private Restrictions restrictions;
    private WriteTransform transform;
    private final WriteTransform.Writer stage = new WriteTransform.Writer() {
        @Override
        public boolean set(int x, int y, int z, BlockState state, CompoundTag tile) {
            return stage(x, y, z, state, tile);
        }

        @Override
        public boolean setBiome(int x, int y, int z, Holder<Biome> biome) {
            return stageBiome(x, y, z, biome);
        }
    };
    private boolean history = true;
    private volatile boolean committed;
    private volatile boolean used;
    private volatile boolean cancelled;
    private volatile boolean applied;
    private volatile boolean stopped;
    private volatile boolean readOnly;
    private final AtomicBoolean staging = new AtomicBoolean();
    private volatile Map<Long, CompletableFuture<Applier.Applied>> streamed;
    private volatile ChangeSet record;
    private volatile boolean captureTiles;
    private volatile Set<SideEffect> effects = SideEffect.defaults();
    private final AtomicInteger hintLow = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger hintHigh = new AtomicInteger(Integer.MIN_VALUE);

    public Edit(KWorld world, long limit) {
        this.world = world;
        this.limit = limit < 0 ? Long.MAX_VALUE : limit;
    }

    public KWorld world() {
        return world;
    }

    public void mask(Mask value) {
        this.mask = value;
    }

    public Mask mask() {
        return mask;
    }

    public void transform(WriteTransform value) {
        this.transform = value;
    }

    public WriteTransform transform() {
        return transform;
    }

    public void restrictions(Restrictions value) {
        this.restrictions = value;
    }

    public boolean plain() {
        return transform == null && mask == null && restrictions == null && limit == Long.MAX_VALUE;
    }

    public boolean pending(int chunkX, int chunkZ, int sectionY) {
        ChunkChanges existing = changes.get(ChunkKey.of(chunkX, chunkZ));
        if (existing == null) {
            return false;
        }
        int index = sectionY - world.minSection();
        return index >= 0 && index < existing.sections().length && existing.sections()[index] != null;
    }

    public PalettedContainer<BlockState> section(int chunkX, int chunkZ, int sectionY) {
        ChunkSnap snap = snap(chunkX, chunkZ, sectionY, sectionY);
        return snap.states(sectionY - world.minSection());
    }

    public void fillSection(int chunkX, int chunkZ, int sectionY, BlockState state) {
        writable();
        ChunkChanges target = changes(chunkX, chunkZ);
        unlimited.add(target.fill(sectionY - world.minSection(), state));
    }

    public boolean allows(int x, int y, int z) {
        Restrictions current = restrictions;
        return current == null || current.allows(x, y, z);
    }

    public Predicate<Entity> allowing(Predicate<Entity> filter) {
        if (restrictions == null) {
            return filter;
        }
        return entity -> {
            BlockPos at = entity.blockPosition();
            return allows(at.getX(), at.getY(), at.getZ()) && filter.test(entity);
        };
    }

    public List<Entities.Placement> allowed(List<Entities.Placement> placements) {
        if (restrictions == null) {
            return placements;
        }
        List<Entities.Placement> out = new ArrayList<>(placements.size());
        for (Entities.Placement placement : placements) {
            BlockVec at = placement.position().toBlock();
            if (allows(at.x(), at.y(), at.z())) {
                out.add(placement);
            }
        }
        return out;
    }

    public List<EntityRecord> allowedRecords(List<EntityRecord> records) {
        if (restrictions == null) {
            return records;
        }
        List<EntityRecord> out = new ArrayList<>(records.size());
        for (EntityRecord record : records) {
            BlockVec at = record.data().position().toBlock();
            if (allows(at.x(), at.y(), at.z())) {
                out.add(record);
            }
        }
        return out;
    }

    public void sourceMask(Mask value) {
        this.sourceMask = value;
    }

    public Mask sourceMask() {
        return sourceMask;
    }

    public void captureTiles() {
        synchronized (snaps) {
            if (captureTiles) {
                return;
            }
            if (!snaps.isEmpty()) {
                throw new IllegalStateException("captureTiles() must be called before the edit reads the world");
            }
            captureTiles = true;
        }
    }

    public boolean capturesTiles() {
        return captureTiles;
    }

    public void sideEffects(Set<SideEffect> value) {
        this.effects = Set.copyOf(value);
    }

    public boolean staged(int x, int y, int z) {
        ChunkChanges chunk = changes.get(ChunkKey.of(x >> 4, z >> 4));
        return chunk != null && chunk.get(x, y, z) != null;
    }

    public boolean touchesStaged(AABB box) {
        int x1 = (int) Math.floor(box.maxX - 1.0E-7);
        int y1 = (int) Math.floor(box.maxY - 1.0E-7);
        int z1 = (int) Math.floor(box.maxZ - 1.0E-7);
        for (int x = (int) Math.floor(box.minX); x <= x1; x++) {
            for (int y = (int) Math.floor(box.minY); y <= y1; y++) {
                for (int z = (int) Math.floor(box.minZ); z <= z1; z++) {
                    if (staged(x, y, z)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean entityEvents() {
        return effects.contains(SideEffect.EVENTS);
    }

    public void history(boolean value) {
        this.history = value;
    }

    public boolean history() {
        return history;
    }

    @Override
    public int minY() {
        return world.minY();
    }

    @Override
    public int maxY() {
        return world.maxY();
    }

    public long changed() {
        return limit == Long.MAX_VALUE ? unlimited.sum() : changed.get();
    }

    public long limit() {
        return limit;
    }

    private static final class Cache {
        long key = Long.MIN_VALUE;
        ChunkSnap snap;
    }

    private ChunkSnap snap(int chunkX, int chunkZ, int fromSection, int toSection) {
        long key = ChunkKey.of(chunkX, chunkZ);
        Cache local = cache.get();
        if (local.key == key && local.snap.covers(fromSection, toSection)) {
            return local.snap;
        }
        CompletableFuture<ChunkSnap> future = request(chunkX, chunkZ, key);
        ChunkSnap snap = await(future);
        if (!snap.covers(fromSection, toSection)) {
            snap = await(snaps.compute(key, (k, current) -> current == future ? capture(chunkX, chunkZ, Integer.MIN_VALUE, Integer.MAX_VALUE) : current));
        }
        local.key = key;
        local.snap = snap;
        return snap;
    }

    private ChunkSnap snap(int chunkX, int chunkZ, int y) {
        return snap(chunkX, chunkZ, y >> 4, y >> 4);
    }

    private static ChunkSnap await(CompletableFuture<ChunkSnap> future) {
        ChunkSnap snap = future.getNow(null);
        return snap != null ? snap : Sched.join(future);
    }

    private CompletableFuture<ChunkSnap> capture(int chunkX, int chunkZ, int fromSection, int toSection) {
        boolean tiles = captureTiles;
        return world.queue().submit(chunkX, chunkZ, chunk -> ChunkSnap.capture(chunk, world, tiles, fromSection, toSection));
    }

    private CompletableFuture<ChunkSnap> request(int chunkX, int chunkZ, long key) {
        CompletableFuture<ChunkSnap> existing = snaps.get(key);
        if (existing != null) {
            return existing;
        }
        int low = hintLow.get();
        int high = hintHigh.get();
        boolean hinted = low <= high;
        synchronized (snaps) {
            return snaps.computeIfAbsent(key, k -> capture(chunkX, chunkZ, hinted ? low - 1 : Integer.MIN_VALUE, hinted ? high + 1 : Integer.MAX_VALUE));
        }
    }

    private static final int PREFETCH_BATCH = 512;

    public void prefetch(Collection<Long> keys, int minY, int maxY) {
        if (!readOnly && staging.compareAndSet(false, true)) {
            world.queue().staging(1);
        }
        used = true;
        hintLow.accumulateAndGet(Math.min(minY, maxY) >> 4, Math::min);
        hintHigh.accumulateAndGet(Math.max(minY, maxY) >> 4, Math::max);
        List<CompletableFuture<ChunkSnap>> pending = new ArrayList<>(Math.min(keys.size(), PREFETCH_BATCH));
        for (long key : keys) {
            pending.add(request(ChunkKey.x(key), ChunkKey.z(key), key));
            if (pending.size() >= PREFETCH_BATCH) {
                Sched.join(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
                pending.clear();
            }
        }
        Sched.join(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
    }

    public BlockState original(int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return AIR;
        }
        return snap(x >> 4, z >> 4, y).get(x, y, z);
    }

    @Override
    public BlockState get(int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return AIR;
        }
        ChunkChanges pending = changes.get(ChunkKey.of(x >> 4, z >> 4));
        if (pending != null) {
            BlockState state = pending.get(x, y, z);
            if (state != null) {
                return state;
            }
        }
        return snap(x >> 4, z >> 4, y).get(x, y, z);
    }

    public CompoundTag originalTile(int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return null;
        }
        return snap(x >> 4, z >> 4, y).tile(x, y, z);
    }

    @Override
    public CompoundTag tile(int x, int y, int z) {
        if (y < world.minY() || y > world.maxY()) {
            return null;
        }
        ChunkChanges pending = changes.get(ChunkKey.of(x >> 4, z >> 4));
        if (pending != null) {
            BlockState state = pending.get(x, y, z);
            if (state != null) {
                CompoundTag tag = pending.tileAt(x, y, z);
                return tag == null ? null : tag.copy();
            }
        }
        return snap(x >> 4, z >> 4, y).tile(x, y, z);
    }

    @Override
    public Holder<Biome> biome(int x, int y, int z) {
        int clamped = Math.clamp(y, world.minY(), world.maxY());
        ChunkChanges pending = changes.get(ChunkKey.of(x >> 4, z >> 4));
        if (pending != null) {
            Holder<Biome> biome = pending.biomeAt(x, clamped, z);
            if (biome != null) {
                return biome;
            }
        }
        return snap(x >> 4, z >> 4, clamped).biome(x, clamped, z);
    }

    public Holder<Biome> originalBiome(int x, int y, int z) {
        int clamped = Math.clamp(y, world.minY(), world.maxY());
        return snap(x >> 4, z >> 4, clamped).biome(x, clamped, z);
    }

    public boolean cancel() {
        if (applied || cancelled) {
            return false;
        }
        cancelled = true;
        return true;
    }

    public boolean stopped() {
        return stopped;
    }

    private void writable() {
        if (cancelled) {
            throw new EditCancelledException();
        }
        if (readOnly) {
            throw new IllegalStateException("Edit is read only");
        }
        if (committed) {
            throw new IllegalStateException("Edit was already committed");
        }
    }

    public static Edit reader(KWorld world) {
        Edit edit = new Edit(world, -1);
        edit.readOnly = true;
        edit.history = false;
        return edit;
    }

    public ChangeSet record() {
        return record;
    }

    private void count() {
        if (limit == Long.MAX_VALUE) {
            unlimited.increment();
            return;
        }
        if (changed.incrementAndGet() > limit) {
            changed.decrementAndGet();
            throw new MaxChangedBlocksException(limit);
        }
    }

    public int surface(int x, int z) {
        return snap(x >> 4, z >> 4, world.minSection(), world.minSection() + world.sections() - 1).surface(x, z);
    }

    @Override
    public boolean set(int x, int y, int z, BlockState state) {
        return set(x, y, z, state, null);
    }

    @Override
    public boolean set(int x, int y, int z, BlockState state, CompoundTag tile) {
        WriteTransform current = transform;
        if (current != null) {
            writable();
            return current.write(stage, x, y, z, state, tile);
        }
        return stage(x, y, z, state, tile);
    }

    private boolean stage(int x, int y, int z, BlockState state, CompoundTag tile) {
        if (y < world.minY() || y > world.maxY() || !allows(x, y, z)) {
            return false;
        }
        writable();
        if (mask != null && !mask.test(x, y, z)) {
            return false;
        }
        ChunkChanges target = changes(x >> 4, z >> 4);
        synchronized (target) {
            if (target.get(x, y, z) == null) {
                count();
            }
            target.set(x, y, z, state);
            if (tile != null) {
                target.tile(x, y, z, tile.copy());
            }
        }
        return true;
    }

    @Override
    public boolean setBiome(int x, int y, int z, Holder<Biome> biome) {
        WriteTransform current = transform;
        if (current != null) {
            writable();
            return current.writeBiome(stage, x, y, z, biome);
        }
        return stageBiome(x, y, z, biome);
    }

    private boolean stageBiome(int x, int y, int z, Holder<Biome> biome) {
        writable();
        if (biome == null || y < world.minY() || y > world.maxY() || !allows(x, y, z)) {
            return false;
        }
        if (mask != null && !mask.test(x, y, z)) {
            return false;
        }
        ChunkChanges target = changes(x >> 4, z >> 4);
        synchronized (target) {
            if (target.biomeAt(x, y, z) == null) {
                count();
            }
            target.biome(x, y, z, biome);
        }
        return true;
    }

    private ChunkChanges changes(int chunkX, int chunkZ) {
        long key = ChunkKey.of(chunkX, chunkZ);
        ChunkChanges existing = changes.get(key);
        if (existing != null) {
            return existing;
        }
        return changes.computeIfAbsent(key, k -> new ChunkChanges(chunkX, chunkZ, world.minSection(), world.sections()));
    }

    public boolean used() {
        return used || committed;
    }

    public void stream() {
        writable();
        if (transform != null || streamed != null || !(Sched.folia() ? Tuning.get().foliaStreamEdits() : Tuning.get().streamEdits())) {
            return;
        }
        record = history ? new ChangeSet(world) : null;
        streamed = new ConcurrentHashMap<>();
    }

    public boolean streaming() {
        return streamed != null;
    }

    public void flush(long key) {
        Map<Long, CompletableFuture<Applier.Applied>> out = streamed;
        if (out == null || cancelled) {
            return;
        }
        ChunkChanges chunk = changes.get(key);
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        chunk.seal();
        Applier.Prepared prepared = null;
        CompletableFuture<ChunkSnap> snap = snaps.get(key);
        if (Applier.direct(effects) && snap != null && snap.isDone() && !snap.isCompletedExceptionally()) {
            prepared = Applier.prepare(world, chunk, snap.join());
        }
        if (Debug.timings()) {
            Debug.time("flush submit " + chunk.chunkX + "," + chunk.chunkZ + " on " + Thread.currentThread().getName());
        }
        out.put(key, submit(world, chunk, record, effects, prepared, this::stopping, null));
    }

    public void staged() {
        if (staging.compareAndSet(true, false)) {
            world.queue().staging(-1);
        }
    }

    public CompletableFuture<Result> commit() {
        Map<Long, CompletableFuture<Applier.Applied>> already = streamed;
        if (already == null) {
            writable();
            record = history ? new ChangeSet(world) : null;
        } else if (readOnly || committed) {
            writable();
        }
        committed = true;
        Map<Long, CompletableFuture<Applier.Applied>> done = already == null ? Map.of() : already;
        Map<Long, Applier.Prepared> ready = Applier.direct(effects) ? prepare(world, changes, snaps, done) : Map.of();
        CompletableFuture<Result> result = commit(world, changes, record, effects, ready, this::stopping, done).whenComplete((value, error) -> applied = true);
        staged();
        return result;
    }

    private boolean stopping() {
        if (cancelled) {
            stopped = true;
        }
        return cancelled;
    }

    private static Map<Long, Applier.Prepared> prepare(KWorld world, Map<Long, ChunkChanges> changes, Map<Long, CompletableFuture<ChunkSnap>> snaps,
                                                       Map<Long, CompletableFuture<Applier.Applied>> skip) {
        Map<Long, Applier.Prepared> out = new ConcurrentHashMap<>();
        List<Map.Entry<Long, ChunkChanges>> entries = new ArrayList<>(changes.size());
        for (Map.Entry<Long, ChunkChanges> entry : changes.entrySet()) {
            if (!skip.containsKey(entry.getKey())) {
                entries.add(entry);
            }
        }
        Sched.parallel().submit(() -> entries.parallelStream().forEach(entry -> {
            CompletableFuture<ChunkSnap> future = snaps.get(entry.getKey());
            if (future == null || !future.isDone() || future.isCompletedExceptionally() || entry.getValue().isEmpty()) {
                return;
            }
            Applier.Prepared prepared = Applier.prepare(world, entry.getValue(), future.join());
            if (prepared != null) {
                out.put(entry.getKey(), prepared);
            }
        })).join();
        return out;
    }

    public static CompletableFuture<Result> commit(KWorld world, Map<Long, ChunkChanges> changes, ChangeSet record, Set<SideEffect> effects) {
        if (!Applier.direct(effects)) {
            return commit(world, changes, record, effects, Map.of(), () -> false, Map.of());
        }
        Map<Long, CompletableFuture<ChunkSnap>> snaps = new HashMap<>();
        for (Map.Entry<Long, ChunkChanges> entry : changes.entrySet()) {
            ChunkChanges chunk = entry.getValue();
            int[] range = chunk.isEmpty() ? null : Applier.swapRange(chunk);
            if (range != null) {
                int low = range[0] + chunk.minSection();
                int high = range[1] + chunk.minSection();
                snaps.put(entry.getKey(), world.queue().submit(chunk.chunkX, chunk.chunkZ, live -> ChunkSnap.capture(live, world, false, low, high)));
            }
        }
        for (CompletableFuture<ChunkSnap> future : snaps.values()) {
            future.handle((value, error) -> null).join();
        }
        return commit(world, changes, record, effects, prepare(world, changes, snaps, Map.of()), () -> false, Map.of());
    }

    private static CompletableFuture<Result> commit(KWorld world, Map<Long, ChunkChanges> changes, ChangeSet record, Set<SideEffect> effects,
                                                    Map<Long, Applier.Prepared> prepared, BooleanSupplier stop,
                                                    Map<Long, CompletableFuture<Applier.Applied>> streamed) {
        List<CompletableFuture<Applier.Applied>> applied = new ArrayList<>(changes.size());
        boolean timed = Debug.timings();
        long commitStart = timed ? System.nanoTime() : 0L;
        AtomicLong applyNanos = timed ? new AtomicLong() : null;
        for (ChunkChanges chunk : changes.values()) {
            if (chunk.isEmpty()) {
                continue;
            }
            long key = ChunkKey.of(chunk.chunkX, chunk.chunkZ);
            CompletableFuture<Applier.Applied> already = streamed.get(key);
            applied.add(already != null ? already : submit(world, chunk, record, effects, prepared.get(key), stop, applyNanos));
        }
        if (timed) {
            CompletableFuture.allOf(applied.toArray(CompletableFuture[]::new)).whenComplete((v, e) -> Debug.time(
                    "apply chunks=" + applied.size() + " wall=" + (System.nanoTime() - commitStart) / 1_000_000L + "ms work=" + applyNanos.get() / 1_000_000L + "ms"));
        }
        CompletableFuture<?>[] settled = new CompletableFuture<?>[applied.size()];
        for (int i = 0; i < applied.size(); i++) {
            settled[i] = applied.get(i).handle((value, error) -> null);
        }
        List<ChunkChanges> order = new ArrayList<>(applied.size());
        for (ChunkChanges chunk : changes.values()) {
            if (!chunk.isEmpty()) {
                order.add(chunk);
            }
        }
        return CompletableFuture.allOf(settled).thenApply(v -> {
            long blocks = 0;
            long biomes = 0;
            long tileFailures = 0;
            List<Throwable> failures = new ArrayList<>();
            List<ChunkPos> relight = new ArrayList<>();
            for (int i = 0; i < applied.size(); i++) {
                try {
                    Applier.Applied result = applied.get(i).join();
                    blocks += result.blocks();
                    biomes += result.biomes();
                    tileFailures += result.tileFailures();
                    if (result.relight()) {
                        relight.add(new ChunkPos(order.get(i).chunkX, order.get(i).chunkZ));
                    }
                } catch (CompletionException | CancellationException e) {
                    failures.add(e.getCause() != null ? e.getCause() : e);
                }
            }
            relight(world, relight);
            return new Result(blocks, biomes, tileFailures, record, List.copyOf(failures));
        });
    }

    private static CompletableFuture<Applier.Applied> submit(KWorld world, ChunkChanges chunk, ChangeSet record, Set<SideEffect> effects, Applier.Prepared prepared,
                                                            BooleanSupplier stop, AtomicLong applyNanos) {
        return world.queue().submit(chunk.chunkX, chunk.chunkZ, live -> {
            if (Debug.timings()) {
                Debug.time("apply start " + chunk.chunkX + "," + chunk.chunkZ + " on " + Thread.currentThread().getName());
            }
            if (stop.getAsBoolean()) {
                return Applier.Applied.NONE;
            }
            if (applyNanos == null) {
                return Applier.apply(world, live, chunk, record, effects, prepared);
            }
            long start = System.nanoTime();
            Applier.Applied result = Applier.apply(world, live, chunk, record, effects, prepared);
            applyNanos.addAndGet(System.nanoTime() - start);
            return result;
        });
    }

    private static void relight(KWorld world, List<ChunkPos> chunks) {
        if (chunks.isEmpty() || !Tuning.get().lighting()) {
            return;
        }
        if (!Sched.folia()) {
            ChunkPos first = chunks.getFirst();
            world.queue().submit(first.x(), first.z(), live -> {
                Light.relight(world, chunks, count -> {
                });
                return null;
            });
            return;
        }
        for (ChunkPos pos : chunks) {
            world.queue().submit(pos.x(), pos.z(), live -> {
                Light.relight(world, List.of(pos), count -> {
                });
                return null;
            });
        }
    }

    public record Result(long changed, long biomes, long tileFailures, ChangeSet history, List<Throwable> failures) {
        public boolean failed() {
            return !failures.isEmpty();
        }
    }
}
