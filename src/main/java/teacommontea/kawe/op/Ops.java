package teacommontea.kawe.op;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import teacommontea.kawe.Debug;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Tuning;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.EditCancelledException;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.CylinderRegion;
import teacommontea.kawe.region.Polygonal2DRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.RegionOperationException;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongConsumer;

public final class Ops {

    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    private Ops() {
    }

    public static void parallelChunks(Edit edit, Region region, LongConsumer perChunk) {
        List<Long> keys = new ArrayList<>(region.chunks());
        boolean timed = Debug.timings();
        long t0 = timed ? System.nanoTime() : 0L;
        edit.prefetch(keys, region.min().y(), region.max().y());
        long t1 = timed ? System.nanoTime() : 0L;
        Sched.parallel().submit(() -> keys.parallelStream().forEach(key -> {
            try {
                perChunk.accept(key);
            } catch (EditCancelledException cancelled) {
                if (!edit.streaming()) {
                    throw cancelled;
                }
                return;
            }
            edit.flush(key);
        })).join();
        if (timed) {
            Debug.time("chunks=" + keys.size() + " prefetch=" + (t1 - t0) / 1_000_000L + "ms stage=" + (System.nanoTime() - t1) / 1_000_000L + "ms");
        }
    }

    public static long setBlocks(Edit edit, Region region, Pattern pattern) {
        if (Tuning.get().sectionFill() && pattern instanceof Pattern.Single single && region instanceof CuboidRegion cuboid && edit.plain()) {
            return fillSections(edit, cuboid, null, single.block());
        }
        LongAdder count = new LongAdder();
        parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            if (pattern.apply(edit, x, y, z)) {
                count.increment();
            }
        }));
        return count.sum();
    }

    public static long replaceBlocks(Edit edit, Region region, Mask mask, Pattern pattern) {
        if (Tuning.get().sectionFill() && mask instanceof Masks.StateMask states && states.extent() == edit && pattern instanceof Pattern.Single single
                && region instanceof CuboidRegion cuboid && edit.plain()) {
            return fillSections(edit, cuboid, states, single.block());
        }
        LongAdder count = new LongAdder();
        parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            if (mask.test(x, y, z) && pattern.apply(edit, x, y, z)) {
                count.increment();
            }
        }));
        return count.sum();
    }

    private static long fillSections(Edit edit, CuboidRegion region, Masks.StateMask mask, BlockState state) {
        LongAdder count = new LongAdder();
        LongAdder filled = new LongAdder();
        LongAdder skipped = new LongAdder();
        LongAdder walked = new LongAdder();
        BlockVec min = region.min();
        BlockVec max = region.max();
        int lowY = Math.max(min.y(), edit.minY());
        int highY = Math.min(max.y(), edit.maxY());
        parallelChunks(edit, region, key -> {
            int chunkX = ChunkKey.x(key);
            int chunkZ = ChunkKey.z(key);
            int x0 = Math.max(min.x(), chunkX << 4);
            int x1 = Math.min(max.x(), (chunkX << 4) + 15);
            int z0 = Math.max(min.z(), chunkZ << 4);
            int z1 = Math.min(max.z(), (chunkZ << 4) + 15);
            boolean fullColumn = x1 - x0 == 15 && z1 - z0 == 15;
            for (int sectionY = lowY >> 4; sectionY <= highY >> 4; sectionY++) {
                int y0 = Math.max(lowY, sectionY << 4);
                int y1 = Math.min(highY, (sectionY << 4) + 15);
                if (fullColumn && y1 - y0 == 15 && !edit.pending(chunkX, chunkZ, sectionY)) {
                    int match = mask == null ? 1 : matches(edit.section(chunkX, chunkZ, sectionY), mask.ids());
                    if (match == 0) {
                        skipped.increment();
                        continue;
                    }
                    if (match == 1) {
                        edit.fillSection(chunkX, chunkZ, sectionY, state);
                        count.add(4096);
                        filled.increment();
                        continue;
                    }
                }
                walked.increment();
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            if ((mask == null || mask.test(x, y, z)) && edit.set(x, y, z, state)) {
                                count.increment();
                            }
                        }
                    }
                }
            }
        });
        if (Debug.timings()) {
            Debug.time("sections filled=" + filled.sum() + " skipped=" + skipped.sum() + " walked=" + walked.sum());
        }
        return count.sum();
    }

    private static int matches(PalettedContainer<BlockState> section, BitSet ids) {
        if (section == null) {
            return ids.get(Block.getId(Blocks.AIR.defaultBlockState())) ? 1 : 0;
        }
        Palette<BlockState> palette = section.data.palette();
        int size = palette.getSize();
        int hits = 0;
        for (int i = 0; i < size; i++) {
            if (ids.get(Block.getId(palette.valueFor(i)))) {
                hits++;
            }
        }
        if (hits == size) {
            return 1;
        }
        if (hits == 0) {
            return 0;
        }
        boolean[] seen = new boolean[2];
        section.count((state, amount) -> {
            if (amount > 0) {
                seen[ids.get(Block.getId(state)) ? 0 : 1] = true;
            }
        });
        return seen[1] ? (seen[0] ? -1 : 0) : 1;
    }

    public static long countBlocks(Edit edit, Region region, Mask mask) {
        LongAdder count = new LongAdder();
        parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            if (mask.test(x, y, z)) {
                count.increment();
            }
        }));
        return count.sum();
    }

    public static long walls(Edit edit, Region region, Pattern pattern) {
        Mask boundary;
        if (region instanceof CuboidRegion cuboid) {
            BlockVec min = cuboid.min();
            BlockVec max = cuboid.max();
            boundary = (x, y, z) -> x == min.x() || x == max.x() || z == min.z() || z == max.z();
        } else {
            boundary = (x, y, z) -> !region.contains(x, y, z + 1) || !region.contains(x, y, z - 1)
                    || !region.contains(x + 1, y, z) || !region.contains(x - 1, y, z);
        }
        return replaceBlocks(edit, region, boundary, pattern);
    }

    public static long faces(Edit edit, Region region, Pattern pattern) {
        Mask boundary;
        if (region instanceof CuboidRegion cuboid) {
            BlockVec min = cuboid.min();
            BlockVec max = cuboid.max();
            boundary = (x, y, z) -> x == min.x() || x == max.x() || y == min.y() || y == max.y() || z == min.z() || z == max.z();
        } else {
            boundary = (x, y, z) -> !region.contains(x, y, z + 1) || !region.contains(x, y, z - 1)
                    || !region.contains(x + 1, y, z) || !region.contains(x - 1, y, z)
                    || !region.contains(x, y + 1, z) || !region.contains(x, y - 1, z);
        }
        return replaceBlocks(edit, region, boundary, pattern);
    }

    public static boolean flatContains(Region region, int x, int z) {
        if (region instanceof CylinderRegion cylinder) {
            return cylinder.containsFlat(x, z);
        }
        if (region instanceof Polygonal2DRegion polygon) {
            return polygon.containsFlat(x, z);
        }
        BlockVec min = region.min();
        BlockVec max = region.max();
        return x >= min.x() && x <= max.x() && z >= min.z() && z <= max.z();
    }

    @FunctionalInterface
    public interface LayerFunction {
        boolean isGround(int x, int y, int z);

        default boolean apply(int x, int y, int z, int depth) {
            return false;
        }
    }

    public static void layers(Edit edit, Region region, LayerFunction function) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        int minY = min.y();
        int maxY = max.y();
        parallelChunks(edit, new CuboidRegion(min, max), key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            int x0 = Math.max(min.x(), cx << 4);
            int x1 = Math.min(max.x(), (cx << 4) + 15);
            int z0 = Math.max(min.z(), cz << 4);
            int z1 = Math.min(max.z(), (cz << 4) + 15);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (!flatContains(region, x, z) || function.isGround(x, maxY + 1, z)) {
                        continue;
                    }
                    boolean found = false;
                    int groundY = 0;
                    for (int y = maxY; y >= minY; y--) {
                        if (!found && function.isGround(x, y, z)) {
                            found = true;
                            groundY = y;
                        }
                        if (found && !function.apply(x, y, z, groundY - y)) {
                            break;
                        }
                    }
                }
            }
        });
    }

    public static long overlay(Edit edit, Region region, Pattern pattern) {
        LongAdder count = new LongAdder();
        layers(edit, region, new LayerFunction() {
            @Override
            public boolean isGround(int x, int y, int z) {
                return !edit.get(x, y, z).isAir();
            }

            @Override
            public boolean apply(int x, int y, int z, int depth) {
                if (depth == 0 && pattern.apply(edit, x, y + 1, z)) {
                    count.increment();
                }
                return false;
            }
        });
        return count.sum();
    }

    public static long naturalize(Edit edit, Region region) {
        LongAdder count = new LongAdder();
        layers(edit, region, new LayerFunction() {
            @Override
            public boolean isGround(int x, int y, int z) {
                Block block = edit.get(x, y, z).getBlock();
                return block == Blocks.GRASS_BLOCK || block == Blocks.DIRT || block == Blocks.STONE;
            }

            @Override
            public boolean apply(int x, int y, int z, int depth) {
                if (isGround(x, y, z)) {
                    BlockState target = depth == 0 ? GRASS : depth <= 3 ? DIRT : STONE;
                    if (edit.set(x, y, z, target)) {
                        count.increment();
                    }
                }
                return true;
            }
        });
        return count.sum();
    }

    public static long center(Edit edit, Region region, Pattern pattern) {
        var center = region.center();
        BlockVec low = new BlockVec((int) Math.floor(center.x()), (int) Math.floor(center.y()), (int) Math.floor(center.z()));
        BlockVec high = new BlockVec((int) Math.floor(center.x() + 0.5), (int) Math.floor(center.y() + 0.5), (int) Math.floor(center.z() + 0.5));
        if (high.y() < edit.minY() || low.y() > edit.maxY()) {
            return 0;
        }
        return setBlocks(edit, new CuboidRegion(low.clampY(edit.minY(), edit.maxY()), high.clampY(edit.minY(), edit.maxY())), pattern);
    }

    public static long copy(Edit edit, Region region, BlockVec displacement, int repetitions, Mask mask, Pattern sourceReplacement, boolean biomes) {
        edit.captureTiles();
        LongAdder count = new LongAdder();
        Mask sourceMask = edit.sourceMask();
        Mask include = mask == null ? sourceMask : sourceMask == null ? mask : mask.and(sourceMask);
        edit.prefetch(new ArrayList<>(region.chunks()), region.min().y(), region.max().y());
        if (sourceReplacement != null) {
            parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
                if (include == null || include.test(x, y, z)) {
                    sourceReplacement.apply(edit, x, y, z);
                }
            }));
        }
        for (int i = 1; i <= repetitions; i++) {
            int dx = Math.multiplyExact(displacement.x(), i);
            int dy = Math.multiplyExact(displacement.y(), i);
            int dz = Math.multiplyExact(displacement.z(), i);
            Region target = shifted(region, new BlockVec(dx, dy, dz));
            parallelChunks(edit, target, key -> target.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
                int sx = x - dx;
                int sy = y - dy;
                int sz = z - dz;
                if (include != null && !include.test(sx, sy, sz)) {
                    return;
                }
                BlockState state = edit.original(sx, sy, sz);
                CompoundTag tile = state.hasBlockEntity() ? edit.originalTile(sx, sy, sz) : null;
                if (edit.set(x, y, z, state, tile)) {
                    count.increment();
                }
                if (biomes) {
                    edit.setBiome(x, y, z, edit.originalBiome(sx, sy, sz));
                }
            }));
        }
        return count.sum();
    }

    public static Region shifted(Region region, BlockVec offset) {
        Region copy = region.copy();
        try {
            copy.shift(offset);
        } catch (RegionOperationException e) {
            throw new IllegalArgumentException("The selection cannot be shifted by " + offset, e);
        }
        return copy;
    }
}
