package teacommontea.kawe.op;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.storage.TagValueOutput;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.region.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

public final class Regen {

    private Regen() {
    }

    public static ChunkGenerator generator(KWorld world, Holder<Biome> biome) {
        ChunkGenerator generator = world.level().getChunkSource().getGenerator();
        if (biome == null) {
            return generator;
        }
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            throw new IllegalArgumentException("A regen biome needs a world with vanilla noise generation");
        }
        return new NoiseBasedChunkGenerator(new FixedBiomeSource(biome), noise.generatorSettings());
    }

    public static long regenerate(Edit edit, Region region, ChunkGenerator generator, boolean biomes) {
        KWorld world = edit.world();
        Map<Long, ProtoChunk> chunks = generate(world.level(), generator, new ArrayList<>(region.chunks()));
        LongAdder changed = new LongAdder();
        Ops.parallelChunks(edit, region, key -> {
            ProtoChunk chunk = chunks.get(key);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
                pos.set(x, y, z);
                BlockState state = chunk.getBlockState(pos);
                CompoundTag tile = null;
                if (state.hasBlockEntity()) {
                    BlockEntity entity = chunk.getBlockEntity(pos);
                    if (entity != null) {
                        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registries());
                        entity.saveWithFullMetadata(output);
                        tile = output.buildResult();
                    } else {
                        tile = chunk.getBlockEntityNbt(pos);
                    }
                }
                if (edit.set(x, y, z, state, tile)) {
                    changed.increment();
                }
                if (biomes) {
                    edit.setBiome(x, y, z, chunk.getNoiseBiome(x >> 2, y >> 2, z >> 2));
                }
            });
        });
        return changed.sum();
    }

    private static Map<Long, ProtoChunk> generate(ServerLevel level, ChunkGenerator generator, List<Long> targets) {
        ChunkDependencies needs = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).accumulatedDependencies();
        int radius = needs.getRadius();
        Set<Long> featured = new HashSet<>();
        for (long key : targets) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    featured.add(ChunkKey.of(ChunkKey.x(key) + dx, ChunkKey.z(key) + dz));
                }
            }
        }
        Map<Long, ChunkStatus> wanted = new HashMap<>();
        for (long key : featured) {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkStatus status = needs.get(Math.max(Math.abs(dx), Math.abs(dz)));
                    wanted.merge(ChunkKey.of(cx + dx, cz + dz), status, (a, b) -> a.isOrAfter(b) ? a : b);
                }
            }
        }
        for (long key : featured) {
            wanted.put(key, ChunkStatus.FEATURES);
        }
        Map<Long, ProtoHolder> holders = new HashMap<>();
        for (long key : wanted.keySet()) {
            ChunkPos pos = new ChunkPos(ChunkKey.x(key), ChunkKey.z(key));
            holders.put(key, new ProtoHolder(pos, new ProtoChunk(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null)));
        }
        WorldGenContext context = new WorldGenContext(level, generator, WorldGen.templates(level.getServer()), level.getChunkSource().getLightEngine(),
                Runnable::run, chunk -> {
                });
        for (ChunkStatus status : ChunkStatus.getStatusList()) {
            if (status.isAfter(ChunkStatus.FEATURES)) {
                break;
            }
            ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
            int reach = step.directDependencies().getRadius();
            List<Long> due = new ArrayList<>();
            wanted.forEach((key, target) -> {
                if (target.isOrAfter(status)) {
                    due.add(key);
                }
            });
            if (step.blockStateWriteRadius() <= 0) {
                Sched.parallel().submit(() -> due.parallelStream().forEach(key -> run(context, step, holders, key, reach))).join();
            } else {
                for (long key : due) {
                    run(context, step, holders, key, reach);
                }
            }
        }
        Map<Long, ProtoChunk> out = new HashMap<>();
        for (long key : targets) {
            out.put(key, holders.get(key).chunk);
        }
        return out;
    }

    private static void run(WorldGenContext context, ChunkStep step, Map<Long, ProtoHolder> holders, long key, int reach) {
        ProtoHolder holder = holders.get(key);
        StaticCache2D<GenerationChunkHolder> cache = StaticCache2D.create(ChunkKey.x(key), ChunkKey.z(key), reach,
                (x, z) -> holders.getOrDefault(ChunkKey.of(x, z), ProtoHolder.MISSING));
        CompletableFuture<ChunkAccess> future = step.apply(context, cache, holder.chunk);
        Sched.join(future);
    }

    private static final class ProtoHolder extends GenerationChunkHolder {
        static final ProtoHolder MISSING = new ProtoHolder(new ChunkPos(0, 0), null);
        final ProtoChunk chunk;

        ProtoHolder(ChunkPos pos, ProtoChunk chunk) {
            super(pos);
            this.chunk = chunk;
        }

        @Override
        protected void addSaveDependency(CompletableFuture<?> future) {
        }

        @Override
        public int getTicketLevel() {
            return 0;
        }

        @Override
        public int getQueueLevel() {
            return 0;
        }

        @Override
        public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
            return chunk;
        }

        @Override
        public ChunkAccess getChunkIfPresent(ChunkStatus status) {
            return chunk != null && chunk.getPersistedStatus().isOrAfter(status) ? chunk : null;
        }

        @Override
        public ChunkAccess getLatestChunk() {
            return chunk;
        }

        @Override
        public ChunkStatus getPersistedStatus() {
            return chunk == null ? null : chunk.getPersistedStatus();
        }

        @Override
        public ChunkStatus getLatestStatus() {
            return getPersistedStatus();
        }
    }
}
