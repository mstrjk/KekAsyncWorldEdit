package teacommontea.kawe.op;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.EllipsoidRegion;
import teacommontea.kawe.region.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class UtilityOps {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState ICE = Blocks.ICE.defaultBlockState();
    private static final BlockState SNOW = Blocks.SNOW.defaultBlockState();
    private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final int MAX_LAYERS = 8;
    private static final int FREEZE_LIGHT = 10;

    private UtilityOps() {
    }

    private static int depthOf(double radius) {
        return (int) (radius * 2 + 1);
    }

    private static Mask ball(BlockVec origin, double radius) {
        return new EllipsoidRegion(origin, new Vec3(radius, radius, radius))::contains;
    }

    private static int reach(double radius) {
        return (int) Math.ceil(radius + 0.5);
    }

    private static List<BlockVec> around(BlockVec origin) {
        List<BlockVec> out = new ArrayList<>(27);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    out.add(origin.add(dx, dy, dz));
                }
            }
        }
        return out;
    }

    public static long fill(Edit edit, BlockVec origin, Pattern pattern, double radius, int depth, BlockVec direction) {
        if (direction.equals(BlockVec.UNIT_MINUS_Y)) {
            return fillXZ(edit, origin, pattern, radius, depth, false);
        }
        Mask inside = ball(origin, radius);
        Flood.prefetch(edit, origin, reach(radius), origin.y() - reach(radius), origin.y() + reach(radius));
        Flood.Step step = (fx, fy, fz, x, y, z) -> {
            if (!along(x - fx, direction.x()) || !along(y - fy, direction.y()) || !along(z - fz, direction.z())) {
                return false;
            }
            long offset = (long) (x - origin.x()) * direction.x() + (long) (y - origin.y()) * direction.y() + (long) (z - origin.z()) * direction.z();
            return offset >= 0 && offset < depth && inside.test(x, y, z) && edit.get(x, y, z).isAir();
        };
        return Flood.run(edit, List.of(origin), Flood.AXES, depthOf(radius), step, (x, y, z) -> pattern.apply(edit, x, y, z));
    }

    private static boolean along(int delta, int axis) {
        return delta == 0 || axis == 0 || axis == delta;
    }

    public static long fillXZ(Edit edit, BlockVec origin, Pattern pattern, double radius, int depth, boolean recursive) {
        long lower = (long) origin.y() - depth + 1;
        int low = (int) Math.max(lower, edit.minY());
        int high = Math.min(edit.maxY(), origin.y());
        Mask inside = ball(origin, radius);
        Mask mask = (x, y, z) -> y >= low && y <= high && inside.test(x, y, z) && edit.get(x, y, z).isAir();
        Flood.prefetch(edit, origin, reach(radius), low, high);
        Flood.Step step = recursive
                ? (fx, fy, fz, x, y, z) -> mask.test(x, y, z)
                : (fx, fy, fz, x, y, z) -> (fy == origin.y() || y < fy) && mask.test(x, y, z);
        BlockVec[] directions = recursive ? Flood.AXES : Flood.NON_RISING;
        return Flood.run(edit, List.of(origin), directions, depthOf(radius), step, (x, y, z) -> pattern.apply(edit, x, y, z));
    }

    private static boolean liquid(BlockState state, boolean plants) {
        Block block = state.getBlock();
        if (block == Blocks.WATER || block == Blocks.LAVA || block == Blocks.BUBBLE_COLUMN) {
            return true;
        }
        return plants && (block == Blocks.KELP_PLANT || block == Blocks.KELP || block == Blocks.SEAGRASS || block == Blocks.TALL_SEAGRASS);
    }

    private static boolean waterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    public static long drain(Edit edit, BlockVec origin, double radius, boolean unwaterlog, boolean plants) {
        if (unwaterlog) {
            edit.captureTiles();
        }
        Mask inside = ball(origin, radius);
        Mask mask = (x, y, z) -> {
            if (!inside.test(x, y, z)) {
                return false;
            }
            BlockState state = edit.get(x, y, z);
            return liquid(state, plants) || (unwaterlog && waterlogged(state));
        };
        Flood.prefetch(edit, origin, reach(radius), origin.y() - reach(radius), origin.y() + reach(radius));
        Flood.Visit visit = (x, y, z) -> {
            BlockState state = edit.get(x, y, z);
            if (unwaterlog && waterlogged(state)) {
                return edit.set(x, y, z, state.setValue(BlockStateProperties.WATERLOGGED, false), edit.tile(x, y, z));
            }
            return edit.set(x, y, z, AIR);
        };
        return Flood.run(edit, around(origin), Flood.AXES, depthOf(radius), (fx, fy, fz, x, y, z) -> mask.test(x, y, z), visit);
    }

    public static long fixLiquid(Edit edit, BlockVec origin, double radius, Block fluid) {
        int high = Math.min(origin.y(), edit.maxY());
        Mask inside = ball(origin, radius);
        BlockState source = fluid.defaultBlockState();
        Flood.prefetch(edit, origin, reach(radius), origin.y() - reach(radius), high);
        List<BlockVec> seeds = new ArrayList<>();
        for (BlockVec seed : around(origin)) {
            if (edit.get(seed.x(), seed.y(), seed.z()).getBlock() == fluid) {
                seeds.add(seed);
            }
        }
        Flood.Step step = (fx, fy, fz, x, y, z) -> {
            if (y > high || !inside.test(x, y, z)) {
                return false;
            }
            BlockState state = edit.get(x, y, z);
            return state.getBlock() == fluid || state.isAir();
        };
        return Flood.run(edit, seeds, Flood.NON_RISING, Integer.MAX_VALUE, step, (x, y, z) -> edit.set(x, y, z, source));
    }

    public static long removeAbove(Edit edit, BlockVec position, int apothem, int height) {
        BlockVec min = position.add(-apothem + 1, 0, -apothem + 1);
        BlockVec max = position.add(apothem - 1, height - 1, apothem - 1);
        CuboidRegion area = clampY(edit, min, max);
        return area == null ? 0 : Ops.setBlocks(edit, area, Pattern.of(AIR));
    }

    public static long removeBelow(Edit edit, BlockVec position, int apothem, int height) {
        BlockVec min = position.add(-apothem + 1, -height + 1, -apothem + 1);
        BlockVec max = position.add(apothem - 1, 0, apothem - 1);
        CuboidRegion area = clampY(edit, min, max);
        return area == null ? 0 : Ops.setBlocks(edit, area, Pattern.of(AIR));
    }

    public static long removeNear(Edit edit, BlockVec position, Mask mask, int apothem) {
        BlockVec adjust = BlockVec.ONE.multiply(apothem - 1);
        CuboidRegion area = clampY(edit, position.subtract(adjust), position.add(adjust));
        return area == null ? 0 : Ops.replaceBlocks(edit, area, mask, Pattern.of(AIR));
    }

    public static CuboidRegion clampY(Edit edit, BlockVec min, BlockVec max) {
        if (Math.max(min.y(), max.y()) < edit.minY() || Math.min(min.y(), max.y()) > edit.maxY()) {
            return null;
        }
        return new CuboidRegion(min.clampY(edit.minY(), edit.maxY()), max.clampY(edit.minY(), edit.maxY()));
    }

    private static boolean snowGround(BlockState state, boolean stack) {
        if (state.isAir() || (stack && state.getBlock() == Blocks.SNOW)) {
            return false;
        }
        return state.getBlock() == Blocks.WATER || Masks.blocksMovement(state);
    }

    private static boolean snowSurvives(BlockState below) {
        if (below.is(BlockTags.CANNOT_SUPPORT_SNOW_LAYER)) {
            return false;
        }
        if (below.is(BlockTags.SUPPORT_OVERRIDE_SNOW_LAYER)) {
            return true;
        }
        return Block.isFaceFull(below.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), Direction.UP)
                || (below.getBlock() == Blocks.SNOW && below.getValue(SnowLayerBlock.LAYERS) == MAX_LAYERS);
    }

    public static long snow(Edit edit, Region region, boolean stack) {
        LongAdder affected = new LongAdder();
        Map<Long, List<BlockVec>> freeze = new ConcurrentHashMap<>();
        int top = edit.maxY();
        Ops.layers(edit, region, new Ops.LayerFunction() {
            @Override
            public boolean isGround(int x, int y, int z) {
                return snowGround(edit.get(x, y, z), stack);
            }

            @Override
            public boolean apply(int x, int y, int z, int depth) {
                if (depth > 0) {
                    return false;
                }
                BlockState block = edit.get(x, y, z);
                if (block.getBlock() == Blocks.WATER) {
                    if (block.getValue(BlockStateProperties.LEVEL) == 0) {
                        freeze.computeIfAbsent(ChunkKey.of(x >> 4, z >> 4), k -> new ArrayList<>()).add(new BlockVec(x, y, z));
                    }
                    return false;
                }
                if (y == top) {
                    return false;
                }
                BlockState above = edit.get(x, y + 1, z);
                boolean layered = stack && above.getBlock() == Blocks.SNOW;
                if (!(above.isAir() || layered) || !snowSurvives(block)) {
                    return false;
                }
                boolean placed;
                if (layered) {
                    int layers = above.getValue(SnowLayerBlock.LAYERS);
                    placed = edit.set(x, y + 1, z, layers >= MAX_LAYERS - 1 ? SNOW_BLOCK : above.setValue(SnowLayerBlock.LAYERS, layers + 1));
                } else {
                    placed = edit.set(x, y + 1, z, SNOW);
                }
                if (!placed) {
                    return false;
                }
                if (block.hasProperty(BlockStateProperties.SNOWY)) {
                    edit.set(x, y, z, block.setValue(BlockStateProperties.SNOWY, true));
                }
                affected.increment();
                return false;
            }
        });
        affected.add(freeze(edit, freeze));
        return affected.sum();
    }

    private static long freeze(Edit edit, Map<Long, List<BlockVec>> candidates) {
        List<CompletableFuture<List<BlockVec>>> parts = new ArrayList<>();
        for (Map.Entry<Long, List<BlockVec>> entry : candidates.entrySet()) {
            List<BlockVec> positions = entry.getValue();
            parts.add(edit.world().queue().submit(ChunkKey.x(entry.getKey()), ChunkKey.z(entry.getKey()), chunk -> {
                List<BlockVec> cold = new ArrayList<>();
                ServerLevel level = edit.world().level();
                BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
                for (BlockVec at : positions) {
                    cursor.set(at.x(), at.y(), at.z());
                    if (level.getBrightness(LightLayer.BLOCK, cursor) < FREEZE_LIGHT && level.getBiome(cursor).value().coldEnoughToSnow(cursor, level.getSeaLevel())) {
                        cold.add(at);
                    }
                }
                return List.copyOf(cold);
            }));
        }
        long frozen = 0;
        for (BlockVec at : Sched.joinAll(parts)) {
            if (edit.set(at.x(), at.y(), at.z(), ICE)) {
                frozen++;
            }
        }
        return frozen;
    }

    @FunctionalInterface
    private interface ColumnWork {
        long apply(int x, int z, int low, int high);
    }

    private static long columns(Edit edit, BlockVec position, double radius, int height, ColumnWork work) {
        double radiusSq = radius * radius;
        int low = (int) Math.max(edit.minY(), (long) position.y() - height);
        int high = (int) Math.min(edit.maxY(), (long) position.y() + height);
        if (low > high) {
            return 0;
        }
        int ceil = (int) Math.ceil(radius);
        CuboidRegion box = new CuboidRegion(new BlockVec(position.x() - ceil, low, position.z() - ceil), new BlockVec(position.x() + ceil, high, position.z() + ceil));
        LongAdder affected = new LongAdder();
        Ops.parallelChunks(edit, box, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            for (int x = Math.max(box.min().x(), cx << 4); x <= Math.min(box.max().x(), (cx << 4) + 15); x++) {
                for (int z = Math.max(box.min().z(), cz << 4); z <= Math.min(box.max().z(), (cz << 4) + 15); z++) {
                    double dx = x - position.x();
                    double dz = z - position.z();
                    if (dx * dx + dz * dz <= radiusSq) {
                        affected.add(work.apply(x, z, low, high));
                    }
                }
            }
        });
        return affected.sum();
    }

    public static long thaw(Edit edit, BlockVec position, double radius, int height) {
        return columns(edit, position, radius, height, (x, z, low, high) -> {
            for (int y = high; y >= low; y--) {
                Block block = edit.get(x, y, z).getBlock();
                if (block == Blocks.ICE) {
                    return edit.set(x, y, z, WATER) ? 1 : 0;
                }
                if (block == Blocks.SNOW) {
                    if (!edit.set(x, y, z, AIR)) {
                        return 0;
                    }
                    long changed = 1;
                    BlockState below = edit.get(x, y - 1, z);
                    if (below.hasProperty(BlockStateProperties.SNOWY) && edit.set(x, y - 1, z, below.setValue(BlockStateProperties.SNOWY, false))) {
                        changed++;
                    }
                    return changed;
                }
                if (!edit.get(x, y, z).isAir()) {
                    return 0;
                }
            }
            return 0;
        });
    }

    public static long green(Edit edit, BlockVec position, double radius, int height, boolean onlyNormalDirt) {
        return columns(edit, position, radius, height, (x, z, low, high) -> {
            for (int y = high; y >= low; y--) {
                BlockState state = edit.get(x, y, z);
                Block block = state.getBlock();
                if (block == Blocks.DIRT || (!onlyNormalDirt && block == Blocks.COARSE_DIRT)) {
                    BlockState above = edit.get(x, y + 1, z);
                    if (Masks.blocksMovement(above) || !above.getFluidState().isEmpty()) {
                        return 0;
                    }
                    return edit.set(x, y, z, GRASS) ? 1 : 0;
                }
                if (block == Blocks.WATER || block == Blocks.LAVA || Masks.blocksMovement(state)) {
                    return 0;
                }
            }
            return 0;
        });
    }
}
