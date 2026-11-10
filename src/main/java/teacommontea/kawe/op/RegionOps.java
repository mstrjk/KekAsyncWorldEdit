package teacommontea.kawe.op;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Spline;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

public final class RegionOps {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int[][] FACES = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    private RegionOps() {
    }

    private static final double MIN_SPLINE_STEP = 1.0E-6;

    private static long key(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    public static long place(Edit edit, LongOpenHashSet positions, Pattern pattern) {
        long changed = 0;
        LongIterator iterator = positions.iterator();
        while (iterator.hasNext()) {
            long at = iterator.nextLong();
            if (pattern.apply(edit, BlockPos.getX(at), BlockPos.getY(at), BlockPos.getZ(at))) {
                changed++;
            }
        }
        return changed;
    }

    public static LongOpenHashSet linePoints(BlockVec from, BlockVec to) {
        LongOpenHashSet points = new LongOpenHashSet();
        int dx = Math.abs(to.x() - from.x());
        int dy = Math.abs(to.y() - from.y());
        int dz = Math.abs(to.z() - from.z());
        int sx = to.x() - from.x() > 0 ? 1 : -1;
        int sy = to.y() - from.y() > 0 ? 1 : -1;
        int sz = to.z() - from.z() > 0 ? 1 : -1;
        int max = Math.max(Math.max(dx, dy), dz);
        if (max == 0) {
            points.add(key(from.x(), from.y(), from.z()));
            return points;
        }
        for (int step = 0; step <= max; step++) {
            int x;
            int y;
            int z;
            if (max == dx) {
                x = from.x() + step * sx;
                y = (int) Math.round(from.y() + step * (double) dy / dx * sy);
                z = (int) Math.round(from.z() + step * (double) dz / dx * sz);
            } else if (max == dy) {
                y = from.y() + step * sy;
                x = (int) Math.round(from.x() + step * (double) dx / dy * sx);
                z = (int) Math.round(from.z() + step * (double) dz / dy * sz);
            } else {
                z = from.z() + step * sz;
                y = (int) Math.round(from.y() + step * (double) dy / dz * sy);
                x = (int) Math.round(from.x() + step * (double) dx / dz * sx);
            }
            points.add(key(x, y, z));
        }
        return points;
    }

    public static LongOpenHashSet balloon(LongOpenHashSet points, double radius) {
        if (radius < 1) {
            return points;
        }
        LongOpenHashSet out = new LongOpenHashSet();
        int ceil = (int) Math.ceil(radius);
        LongIterator iterator = points.iterator();
        while (iterator.hasNext()) {
            long at = iterator.nextLong();
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            for (int ox = -ceil; ox <= ceil; ox++) {
                for (int oy = -ceil; oy <= ceil; oy++) {
                    for (int oz = -ceil; oz <= ceil; oz++) {
                        if (Math.sqrt((double) ox * ox + (double) oy * oy + (double) oz * oz) <= radius) {
                            out.add(key(x + ox, y + oy, z + oz));
                        }
                    }
                }
            }
        }
        return out;
    }

    public static LongOpenHashSet stretch(LongOpenHashSet points, double radius) {
        if (radius < 1) {
            return points;
        }
        LongOpenHashSet out = new LongOpenHashSet();
        int ceil = (int) Math.ceil(radius);
        LongIterator iterator = points.iterator();
        while (iterator.hasNext()) {
            long at = iterator.nextLong();
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            for (int ox = -ceil; ox <= ceil; ox++) {
                for (int oz = -ceil; oz <= ceil; oz++) {
                    if (Math.sqrt((double) ox * ox + (double) oz * oz) <= radius) {
                        out.add(key(x + ox, y, z + oz));
                    }
                }
            }
        }
        return out;
    }

    public static LongOpenHashSet shell(LongOpenHashSet points, boolean flat) {
        LongOpenHashSet out = new LongOpenHashSet();
        LongIterator iterator = points.iterator();
        while (iterator.hasNext()) {
            long at = iterator.nextLong();
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            boolean inside = points.contains(key(x + 1, y, z)) && points.contains(key(x - 1, y, z))
                    && points.contains(key(x, y, z + 1)) && points.contains(key(x, y, z - 1))
                    && (flat || (points.contains(key(x, y + 1, z)) && points.contains(key(x, y - 1, z))));
            if (!inside) {
                out.add(at);
            }
        }
        return out;
    }

    public static long line(Edit edit, BlockVec from, BlockVec to, Pattern pattern, double radius, boolean filled, boolean flat) {
        return polyline(edit, List.of(from, to), pattern, radius, filled, flat);
    }

    public static long polyline(Edit edit, List<BlockVec> nodes, Pattern pattern, double radius, boolean filled, boolean flat) {
        LongOpenHashSet points = new LongOpenHashSet();
        for (int i = 1; i < nodes.size(); i++) {
            points.addAll(linePoints(nodes.get(i - 1), nodes.get(i)));
        }
        LongOpenHashSet shaped = flat ? stretch(points, radius) : balloon(points, radius);
        if (!filled) {
            shaped = shell(shaped, flat);
        }
        return place(edit, shaped, pattern);
    }

    public static long spline(Edit edit, List<BlockVec> nodes, Pattern pattern, double tension, double bias, double continuity,
                              double quality, double radius, boolean filled) {
        List<Vec3> points = new ArrayList<>(nodes.size());
        for (BlockVec node : nodes) {
            points.add(node.toVec3());
        }
        if (!(quality > 0) || Double.isInfinite(quality)) {
            throw new IllegalArgumentException("Spline quality must be a positive number");
        }
        Spline spline = new Spline(points, tension, bias, continuity);
        double length = spline.length();
        LongOpenHashSet trail = new LongOpenHashSet();
        double step = length <= 0 || !Double.isFinite(length) ? 1 : Math.max(MIN_SPLINE_STEP, 1d / length / quality);
        for (double t = 0; t <= 1; t += step) {
            Vec3 at = spline.position(t);
            trail.add(key((int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z())));
        }
        LongOpenHashSet shaped = balloon(trail, radius);
        if (!filled && radius != 0) {
            shaped = shell(shaped, false);
        }
        return place(edit, shaped, pattern);
    }

    private static void push(Region region, LongOpenHashSet seen, LongArrayList queue, int x, int y, int z) {
        long at = key(x, y, z);
        if (region.contains(x, y, z) && seen.add(at)) {
            queue.add(at);
        }
    }

    public static long hollow(Edit edit, Region region, int thickness, Pattern pattern, Mask mask) {
        LongOpenHashSet outside = new LongOpenHashSet();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        region.forEach((x, y, z) -> {
            if (touches(region, outside, x, y, z)) {
                push(region, seen, queue, x, y, z);
            }
        });
        while (!queue.isEmpty()) {
            long at = queue.removeLong(queue.size() - 1);
            int x = BlockPos.getX(at);
            int y = BlockPos.getY(at);
            int z = BlockPos.getZ(at);
            if (mask.test(x, y, z)) {
                continue;
            }
            outside.add(at);
            for (int[] face : FACES) {
                push(region, seen, queue, x + face[0], y + face[1], z + face[2]);
            }
        }
        for (int i = 1; i < thickness; i++) {
            LongOpenHashSet grown = new LongOpenHashSet();
            region.forEach((x, y, z) -> {
                if (touches(region, outside, x, y, z)) {
                    grown.add(key(x, y, z));
                }
            });
            outside.addAll(grown);
        }
        LongAdder changed = new LongAdder();
        region.forEach((x, y, z) -> {
            if (!touches(region, outside, x, y, z) && pattern.apply(edit, x, y, z)) {
                changed.increment();
            }
        });
        return changed.sum();
    }

    private static boolean touches(Region region, LongOpenHashSet set, int x, int y, int z) {
        for (int[] face : FACES) {
            int nx = x + face[0];
            int ny = y + face[1];
            int nz = z + face[2];
            if (!region.contains(nx, ny, nz) || set.contains(key(nx, ny, nz))) {
                return true;
            }
        }
        return false;
    }

    public static long fall(Edit edit, Region region, boolean fullHeight, Pattern replace) {
        edit.captureTiles();
        BlockVec min = region.min();
        BlockVec max = region.max();
        int perform = min.y();
        int check = fullHeight ? edit.minY() : perform;
        int top = max.y();
        LongAdder changed = new LongAdder();
        Ops.parallelChunks(edit, new CuboidRegion(new BlockVec(min.x(), check, min.z()), max), key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int x = Math.max(min.x(), cx << 4); x <= Math.min(max.x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(min.z(), cz << 4); z <= Math.min(max.z(), (cz << 4) + 15); z++) {
                    if (!Ops.flatContains(region, x, z)) {
                        continue;
                    }
                    int free = check;
                    for (int y = check; y <= top; y++) {
                        BlockState state = edit.original(x, y, z);
                        if (y < perform) {
                            if (!state.isAir()) {
                                free = y + 1;
                            }
                            continue;
                        }
                        if (state.isAir()) {
                            continue;
                        }
                        if (free != y) {
                            CompoundTag tile = state.hasBlockEntity() ? edit.originalTile(x, y, z) : null;
                            edit.set(x, free, z, state, tile);
                            replace.apply(edit, x, y, z);
                            changed.add(2);
                        }
                        free++;
                    }
                }
            }
        });
        return changed.sum();
    }

    public static int highest(Edit edit, int x, int z, int minY, int maxY, Mask mask) {
        for (int y = maxY; y >= minY; y--) {
            if (mask == null ? Masks.blocksMovement(edit.get(x, y, z)) : mask.test(x, y, z)) {
                return y;
            }
        }
        return minY;
    }

    private static final float[] GAUSSIAN = gaussian(5, 1.0);

    private static float[] gaussian(int radius, double sigma) {
        int diameter = radius * 2 + 1;
        float[] data = new float[diameter * diameter];
        double sigma22 = 2 * sigma * sigma;
        double constant = Math.PI * sigma22;
        float sum = 0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                float value = (float) (Math.exp(-(x * x + y * y) / sigma22) / constant);
                data[(y + radius) * diameter + x + radius] = value;
                sum += value;
            }
        }
        for (int i = 0; i < data.length; i++) {
            data[i] = data[i] / sum;
        }
        return data;
    }

    private static float[] blur(float[] in, int width, int length, float offset) {
        int diameter = 11;
        int origin = 5;
        float[] out = new float[in.length];
        for (int y = 0; y < length; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0;
                for (int ky = 0; ky < diameter; ky++) {
                    int oy = y + ky - origin;
                    if (oy < 0 || oy >= length) {
                        oy = y;
                    }
                    for (int kx = 0; kx < diameter; kx++) {
                        float f = GAUSSIAN[ky * diameter + kx];
                        if (f == 0) {
                            continue;
                        }
                        int ox = x + kx - origin;
                        if (ox < 0 || ox >= width) {
                            ox = x;
                        }
                        sum += f * in[oy * width + ox];
                    }
                }
                out[y * width + x] = sum + offset;
            }
        }
        return out;
    }

    private static float[] heights(Edit edit, Region region, Mask mask, boolean snow) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        int width = region.width();
        int length = region.length();
        float[] data = new float[width * length];
        Ops.parallelChunks(edit, region, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int x = Math.max(min.x(), cx << 4); x <= Math.min(max.x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(min.z(), cz << 4); z <= Math.min(max.z(), (cz << 4) + 15); z++) {
                    int top = highest(edit, x, z, min.y(), max.y(), mask);
                    float value = top;
                    if (snow) {
                        BlockState upper = edit.get(x, top + 1, z);
                        if (upper.getBlock() == Blocks.SNOW) {
                            value = top + 1 + (upper.getValue(SnowLayerBlock.LAYERS) - 1) / 8f;
                        } else {
                            value = edit.get(x, top, z).isAir() ? top : top + 1;
                        }
                    }
                    data[(z - min.z()) * width + (x - min.x())] = value;
                }
            }
        });
        return data;
    }

    private static void copyColumn(Edit edit, int x, int z, int toY, int fromY) {
        BlockState state = edit.original(x, fromY, z);
        edit.set(x, toY, z, state, state.hasBlockEntity() ? edit.originalTile(x, fromY, z) : null);
    }

    public static long smooth(Edit edit, Region region, int iterations, Mask mask) {
        edit.captureTiles();
        float[] current = heights(edit, region, mask, false);
        int width = region.width();
        int length = region.length();
        float[] filtered = current.clone();
        for (int i = 0; i < iterations; i++) {
            float[] whole = new float[filtered.length];
            for (int j = 0; j < filtered.length; j++) {
                whole[j] = (int) filtered[j];
            }
            filtered = blur(whole, width, length, 0.5f);
            for (int j = 0; j < filtered.length; j++) {
                filtered[j] = (float) Math.floor(filtered[j]);
            }
        }
        return applyHeights(edit, region, current, filtered);
    }

    public static float[] gaussianBlur(float[] in, int width, int length) {
        return blur(in, width, length, 0);
    }

    public static long applyHeights(Edit edit, Region region, float[] current, float[] target) {
        BlockVec min = region.min();
        int width = region.width();
        int originY = min.y();
        int maxY = region.max().y();
        LongAdder changed = new LongAdder();
        Ops.parallelChunks(edit, region, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int x = Math.max(min.x(), cx << 4); x <= Math.min(region.max().x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(min.z(), cz << 4); z <= Math.min(region.max().z(), (cz << 4) + 15); z++) {
                    int index = (z - min.z()) * width + (x - min.x());
                    int cur = (int) current[index];
                    int next = Math.min(maxY, (int) target[index]);
                    double scale = (double) (cur - originY) / (double) (next - originY);
                    if (next > cur) {
                        BlockState existing = edit.original(x, cur, z);
                        if (existing.getBlock() == Blocks.WATER || existing.getBlock() == Blocks.LAVA) {
                            continue;
                        }
                        copyColumn(edit, x, z, next, cur);
                        changed.increment();
                        for (int y = next - 1 - originY; y >= 0; y--) {
                            copyColumn(edit, x, z, originY + y, originY + (int) Math.floor(y * scale));
                            changed.increment();
                        }
                    } else if (cur > next) {
                        for (int y = 0; y < next - originY; y++) {
                            copyColumn(edit, x, z, originY + y, originY + (int) Math.floor(y * scale));
                            changed.increment();
                        }
                        copyColumn(edit, x, z, next, cur);
                        changed.increment();
                        for (int y = next + 1; y <= cur; y++) {
                            edit.set(x, y, z, AIR);
                            changed.increment();
                        }
                    }
                }
            }
        });
        return changed.sum();
    }

    public static long snowSmooth(Edit edit, Region region, int iterations, int snowBlocks, Mask mask) {
        edit.captureTiles();
        float[] current = heights(edit, region, mask, true);
        int width = region.width();
        int length = region.length();
        float[] filtered = current.clone();
        for (int i = 0; i < iterations; i++) {
            filtered = blur(filtered, width, length, 0.0625f);
        }
        return applySnowHeights(edit, region, current, filtered, snowBlocks);
    }

    public static long applySnowHeights(Edit edit, Region region, float[] current, float[] target, int snowBlocks) {
        BlockVec min = region.min();
        int width = region.width();
        int originY = min.y();
        int maxY = region.max().y();
        BlockState snowBlock = Blocks.SNOW_BLOCK.defaultBlockState();
        LongAdder changed = new LongAdder();
        Ops.parallelChunks(edit, region, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int x = Math.max(min.x(), cx << 4); x <= Math.min(region.max().x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(min.z(), cz << 4); z <= Math.min(region.max().z(), (cz << 4) + 15); z++) {
                    int index = (z - min.z()) * width + (x - min.x());
                    float cur = current[index];
                    if (cur == originY) {
                        continue;
                    }
                    float next = Math.min(maxY, target[index]);
                    double scale = (cur - originY) / (double) (next - originY);
                    if (next >= cur) {
                        BlockState existing = edit.original(x, (int) Math.floor(cur), z);
                        if (existing.getBlock() == Blocks.WATER || existing.getBlock() == Blocks.LAVA) {
                            continue;
                        }
                        snowLayer(edit, x, z, next);
                        changed.increment();
                        for (int y = (int) Math.floor(next - 1 - originY); y >= 0; y--) {
                            if (y >= Math.floor(next - 1 - originY - snowBlocks)) {
                                edit.set(x, originY + y, z, snowBlock);
                            } else {
                                copyColumn(edit, x, z, originY + y, originY + (int) Math.floor(y * scale));
                            }
                            changed.increment();
                        }
                    } else {
                        for (int y = 0; y < (int) Math.floor(next - originY); y++) {
                            if (y >= (int) Math.floor(next - originY - snowBlocks)) {
                                edit.set(x, originY + y, z, snowBlock);
                            } else {
                                copyColumn(edit, x, z, originY + y, originY + (int) Math.floor(y * scale));
                            }
                            changed.increment();
                        }
                        snowLayer(edit, x, z, next);
                        changed.increment();
                        for (int y = (int) Math.floor(next + 1); y <= Math.floor(cur); y++) {
                            edit.set(x, y, z, AIR);
                            changed.increment();
                        }
                    }
                }
            }
        });
        return changed.sum();
    }

    private static void snowLayer(Edit edit, int x, int z, float height) {
        int y = (int) Math.floor(height);
        int layers = Math.clamp((int) ((height - y) * 8) + 1, 1, 8);
        edit.set(x, y, z, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
    }

    public static long lay(Edit edit, Region region, Pattern pattern) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        LongAdder changed = new LongAdder();
        Ops.parallelChunks(edit, region, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int x = Math.max(min.x(), cx << 4); x <= Math.min(max.x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(min.z(), cz << 4); z <= Math.min(max.z(), (cz << 4) + 15); z++) {
                    if (!Ops.flatContains(region, x, z)) {
                        continue;
                    }
                    int y = Masks.surface(edit, x, z, max.y(), min.y(), max.y());
                    if (pattern.apply(edit, x, y, z)) {
                        changed.increment();
                    }
                }
            }
        });
        return changed.sum();
    }

    private record Fixed(int x, int y, int z, BlockState state) {
    }

    public static long fixBlocks(Edit edit, Region region) {
        List<CompletableFuture<List<Fixed>>> parts = new ArrayList<>();
        ServerLevel level = edit.world().level();
        for (long chunkKey : region.chunks()) {
            int cx = ChunkKey.x(chunkKey);
            int cz = ChunkKey.z(chunkKey);
            parts.add(edit.world().queue().submit(cx, cz, chunk -> {
                boolean west = Sched.owns(edit.world().bukkit(), cx - 1, cz);
                boolean east = Sched.owns(edit.world().bukkit(), cx + 1, cz);
                boolean north = Sched.owns(edit.world().bukkit(), cx, cz - 1);
                boolean south = Sched.owns(edit.world().bukkit(), cx, cz + 1);
                List<Fixed> out = new ArrayList<>();
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                region.forEachInChunk(cx, cz, (x, y, z) -> {
                    int lx = x & 15;
                    int lz = z & 15;
                    if ((lx == 0 && !west) || (lx == 15 && !east) || (lz == 0 && !north) || (lz == 15 && !south)) {
                        return;
                    }
                    cursor.set(x, y, z);
                    BlockState state = chunk.getBlockState(cursor);
                    if (state.isAir()) {
                        return;
                    }
                    BlockState fixed = Block.updateFromNeighbourShapes(state, level, cursor);
                    if (fixed != state) {
                        out.add(new Fixed(x, y, z, fixed));
                    }
                });
                return out;
            }));
        }
        long changed = 0;
        for (Fixed fixed : Sched.joinAll(parts)) {
            if (edit.set(fixed.x(), fixed.y(), fixed.z(), fixed.state())) {
                changed++;
            }
        }
        return changed;
    }

    public static long forest(Edit edit, Region region, double density, Trees.Kind kind) {
        Map<BlockVec, Trees.Kind> requests = new HashMap<>();
        BlockVec min = region.min();
        BlockVec max = region.max();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                if (!Ops.flatContains(region, x, z) || random.nextDouble() > density) {
                    continue;
                }
                for (int y = max.y(); y >= min.y(); y--) {
                    BlockState state = edit.get(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    if (Masks.solid(state)) {
                        requests.put(new BlockVec(x, y + 1, z), kind);
                    } else if (state.canBeReplaced()) {
                        requests.put(new BlockVec(x, y, z), kind);
                    }
                    break;
                }
            }
        }
        return Trees.plant(edit, requests);
    }

    public static long flora(Edit edit, Region region, double density) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        LongAdder changed = new LongAdder();
        Ops.parallelChunks(edit, region, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int x = Math.max(min.x(), cx << 4); x <= Math.min(max.x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(min.z(), cz << 4); z <= Math.min(max.z(), (cz << 4) + 15); z++) {
                    if (!Ops.flatContains(region, x, z) || random.nextDouble() > density) {
                        continue;
                    }
                    for (int y = max.y(); y >= min.y(); y--) {
                        BlockState ground = edit.get(x, y, z);
                        if (ground.isAir()) {
                            continue;
                        }
                        BlockState plant = Flora.pick(ground.getBlock(), random);
                        if (plant != null && !plant.isAir() && edit.get(x, y + 1, z).isAir() && edit.set(x, y + 1, z, plant)) {
                            changed.increment();
                        }
                        break;
                    }
                }
            }
        });
        return changed.sum();
    }

    private static final class Flora {
        private static final BlockState[] TEMPERATE = table(new Object[][]{{Blocks.SHORT_GRASS, 300}, {Blocks.POPPY, 5}, {Blocks.DANDELION, 5}});
        private static final BlockState[] DESERT = table(new Object[][]{{Blocks.DEAD_BUSH, 30}, {Blocks.CACTUS, 20}, {Blocks.AIR, 300}});
        private static final BlockState[] MUSHROOM = table(new Object[][]{{Blocks.RED_MUSHROOM, 10}, {Blocks.BROWN_MUSHROOM, 10}});
        private static final BlockState[] NETHER = table(new Object[][]{{Blocks.CRIMSON_ROOTS, 10}, {Blocks.CRIMSON_FUNGUS, 20}, {Blocks.WARPED_FUNGUS, 5}});
        private static final BlockState[] WARPED = table(new Object[][]{{Blocks.WARPED_ROOTS, 15}, {Blocks.NETHER_SPROUTS, 20}, {Blocks.WARPED_FUNGUS, 7},
                {Blocks.CRIMSON_ROOTS, 10}});

        private static BlockState[] table(Object[][] entries) {
            List<BlockState> out = new ArrayList<>();
            for (Object[] entry : entries) {
                for (int i = 0; i < (int) entry[1]; i++) {
                    out.add(((Block) entry[0]).defaultBlockState());
                }
            }
            return out.toArray(BlockState[]::new);
        }

        static BlockState pick(Block ground, ThreadLocalRandom random) {
            BlockState[] table;
            if (ground == Blocks.GRASS_BLOCK) {
                table = TEMPERATE;
            } else if (ground == Blocks.SAND || ground == Blocks.RED_SAND) {
                table = DESERT;
            } else if (ground == Blocks.MYCELIUM || ground == Blocks.NETHERRACK) {
                table = MUSHROOM;
            } else if (ground == Blocks.SOUL_SOIL || ground == Blocks.CRIMSON_NYLIUM) {
                table = NETHER;
            } else if (ground == Blocks.WARPED_NYLIUM) {
                table = WARPED;
            } else {
                return null;
            }
            return table[random.nextInt(table.length)];
        }
    }
}
