package teacommontea.kawe.op;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.util.BlockStateListPopulator;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionTimeoutException;
import teacommontea.kawe.expr.WorldExprEnvironment;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Simplex;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

public final class Generation {

    private static final BlockState LEAVES = Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true);
    private static final BlockState LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState PUMPKIN = Blocks.PUMPKIN.defaultBlockState();

    private Generation() {
    }

    public static long pumpkins(Edit edit, BlockVec position, int apothem, double density) {
        Region area = UtilityOps.clampY(edit, position.add(-apothem, -5, -apothem), position.add(apothem, 10, apothem));
        if (area == null) {
            return 0;
        }
        Random random = new Random(ThreadLocalRandom.current().nextLong());
        BlockVec min = area.min();
        BlockVec max = area.max();
        edit.prefetch(area.chunks(), min.y(), max.y());
        long patches = 0;
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                if (random.nextDouble() >= density) {
                    continue;
                }
                for (int y = max.y(); y >= min.y(); y--) {
                    if (edit.get(x, y, z).isAir()) {
                        continue;
                    }
                    if (patch(edit, new BlockVec(x, y, z), random)) {
                        patches++;
                    }
                    break;
                }
            }
        }
        return patches;
    }

    private static boolean patch(Edit edit, BlockVec ground, Random random) {
        BlockVec position = edit.get(ground.x(), ground.y(), ground.z()).isAir() ? ground : ground.add(0, 1, 0);
        if (edit.get(position.x(), position.y() - 1, position.z()).getBlock() != Blocks.GRASS_BLOCK) {
            return false;
        }
        setIfAir(edit, position, LEAVES);
        vine(edit, position, position.add(0, 0, 1), random);
        vine(edit, position, position.add(0, 0, -1), random);
        vine(edit, position, position.add(1, 0, 0), random);
        vine(edit, position, position.add(-1, 0, 0), random);
        return true;
    }

    private static void vine(Edit edit, BlockVec base, BlockVec at, Random random) {
        if (at.distance(base) > 4 || !edit.get(at.x(), at.y(), at.z()).isAir()) {
            return;
        }
        BlockVec pos = at;
        for (int i = -1; i > -3; i--) {
            BlockVec below = pos.add(0, -1, 0);
            if (!edit.get(below.x(), below.y(), below.z()).isAir()) {
                break;
            }
            pos = below;
        }
        setIfAir(edit, pos, LEAVES);
        int turn = random.nextInt(4);
        int lift = random.nextInt(3) - 1;
        int[][] next = {{1, 0}, {0, 1}, {0, -1}, {-1, 0}};
        int[][] log = {{1, -1}, {1, 0}, {-1, 0}, {-1, -1}};
        int[][] fruit = {{0, -1}, {1, 1}, {-1, 1}, {-1, -1}};
        if (random.nextBoolean()) {
            vine(edit, base, pos.add(next[turn][0], 0, next[turn][1]), random);
        }
        if (random.nextBoolean()) {
            setIfAir(edit, pos.add(log[turn][0], lift, log[turn][1]), LOG);
        }
        setIfAir(edit, pos.add(fruit[turn][0], 0, fruit[turn][1]), PUMPKIN);
    }

    private static void setIfAir(Edit edit, BlockVec at, BlockState state) {
        if (edit.get(at.x(), at.y(), at.z()).isAir()) {
            edit.set(at.x(), at.y(), at.z(), state);
        }
    }

    public static void blob(Edit edit, BlockVec position, Pattern pattern, double size, double frequency, double amplitude, Vec3 radius, double sphericity) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Simplex noise = new Simplex(random.nextLong());
        double seedX = random.nextDouble();
        double seedY = random.nextDouble();
        double seedZ = random.nextDouble();
        double distort = frequency / size;
        double modX = 1d / radius.x();
        double modY = 1d / radius.y();
        double modZ = 1d / radius.z();
        double radiusSqr = size * size;
        int reach = (int) Math.ceil(size * 2);
        int px = position.x();
        int py = position.y();
        int pz = position.z();
        double[][] rotation = sphericity >= 1 ? null : rotation(random.nextInt(360), random.nextInt(360), random.nextInt(360));
        double manX = 1.25 + seedX * 0.5;
        double manY = 1.25 + seedY * 0.5;
        double manZ = 1.25 + seedZ * 0.5;
        double roughness = 1 - sphericity;
        Region box = UtilityOps.clampY(edit, new BlockVec(px - reach, py - reach, pz - reach), new BlockVec(px + reach, py + reach, pz + reach));
        if (box == null) {
            return;
        }
        Ops.parallelChunks(edit, box, key -> box.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (xx, yy, zz) -> {
            int xr = xx - px;
            int yr = yy - py;
            int zr = zz - pz;
            if (rotation == null) {
                double distance = xr * xr * modX + yr * yr * modY + zr * zr * modZ;
                double wobble = amplitude * noise.noise(seedX + xr * distort, seedY + yr * distort, seedZ + zr * distort);
                if (distance + distance * wobble < radiusSqr) {
                    pattern.apply(edit, xx, yy, zz);
                }
                return;
            }
            int x = (int) Math.round(rotation[0][0] * xr + rotation[0][1] * yr + rotation[0][2] * zr);
            int y = (int) Math.round(rotation[1][0] * xr + rotation[1][1] * yr + rotation[1][2] * zr);
            int z = (int) Math.round(rotation[2][0] * xr + rotation[2][1] * yr + rotation[2][2] * zr);
            double xScaled = Math.abs(x) * modX;
            double yScaled = Math.abs(y) * modY;
            double zScaled = Math.abs(z) * modZ;
            double manhattan = xScaled + yScaled + zScaled;
            double distance = Math.sqrt(x * x * modX + y * y * modY + z * z * modZ) * sphericity
                    + Math.max(Math.max(manhattan, xScaled * manX), Math.max(yScaled * manY, zScaled * manZ)) * roughness;
            double wobble = amplitude * noise.noise(seedX + x * distort, seedY + y * distort, seedZ + z * distort);
            if (distance + distance * wobble < size) {
                pattern.apply(edit, xx, yy, zz);
            }
        }));
    }

    private static double[][] rotation(int degX, int degY, int degZ) {
        double ax = Math.toRadians(degX);
        double ay = Math.toRadians(degY);
        double az = Math.toRadians(degZ);
        double[][] rx = {{1, 0, 0}, {0, Math.cos(ax), -Math.sin(ax)}, {0, Math.sin(ax), Math.cos(ax)}};
        double[][] ry = {{Math.cos(ay), 0, Math.sin(ay)}, {0, 1, 0}, {-Math.sin(ay), 0, Math.cos(ay)}};
        double[][] rz = {{Math.cos(az), -Math.sin(az), 0}, {Math.sin(az), Math.cos(az), 0}, {0, 0, 1}};
        return multiply(rz, multiply(ry, rx));
    }

    private static double[][] multiply(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    out[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return out;
    }

    public static void ore(Edit edit, Region region, Mask mask, Pattern material, int size, int frequency, int rarity, int minY, int maxY, boolean triangular) {
        if (size <= 0 || frequency <= 0 || rarity <= 0 || minY >= maxY) {
            return;
        }
        Mask inside = (x, y, z) -> region.contains(x, y, z) && (mask == null || mask.test(x, y, z));
        Random random = new Random(ThreadLocalRandom.current().nextLong());
        for (long key : region.chunks()) {
            for (int i = 0; i < frequency; i++) {
                if (random.nextInt(100) >= rarity) {
                    continue;
                }
                int x = (ChunkKey.x(key) << 4) + random.nextInt(16);
                int z = (ChunkKey.z(key) << 4) + random.nextInt(16);
                vein(edit, inside, material, random, x, z, size, minY, maxY, triangular);
            }
        }
    }

    private static void vein(Edit edit, Mask mask, Pattern material, Random random, int x, int z, int size, int lowY, int highY, boolean triangular) {
        int range = highY - lowY;
        int y;
        if (triangular && range >= 2) {
            int mid = range / 2;
            y = lowY + random.nextInt(mid) + random.nextInt(range - mid);
        } else {
            y = lowY + random.nextInt(range);
        }
        if (!mask.test(x, y, z)) {
            return;
        }
        double angle = random.nextDouble() * Math.PI;
        double sf = Math.sin(angle) * size / 8.0;
        double cf = Math.cos(angle) * size / 8.0;
        double x1 = x + sf;
        double x2 = x - sf;
        double z1 = z + cf;
        double z2 = z - cf;
        double y1 = y + random.nextInt(3) - 2;
        double y2 = y + random.nextInt(3) - 2;
        for (int i = 0; i < size; i++) {
            double t = (double) i / size;
            double cx = x1 + (x2 - x1) * t;
            double cy = y1 + (y2 - y1) * t;
            double cz = z1 + (z2 - z1) * t;
            double spread = random.nextDouble() * size / 16.0;
            double swell = (Math.sin(Math.PI * t) + 1.0) * spread + 1.0;
            double half = swell / 2;
            int minX = (int) Math.floor(cx - half);
            int minYy = Math.max(lowY + 1, (int) Math.floor(cy - half));
            int minZ = (int) Math.floor(cz - half);
            int maxX = (int) Math.floor(cx + half);
            int maxYy = Math.min(highY, (int) Math.floor(cy + half));
            int maxZ = (int) Math.floor(cz + half);
            for (int xx = minX; xx <= maxX; xx++) {
                double dx = (xx + 0.5 - cx) / half;
                if (dx * dx >= 1) {
                    continue;
                }
                for (int yy = minYy; yy <= maxYy; yy++) {
                    double dy = (yy + 0.5 - cy) / half;
                    if (dx * dx + dy * dy >= 1) {
                        continue;
                    }
                    for (int zz = minZ; zz <= maxZ; zz++) {
                        double dz = (zz + 0.5 - cz) / half;
                        if (dx * dx + dy * dy + dz * dz < 1 && mask.test(xx, yy, zz)) {
                            material.apply(edit, xx, yy, zz);
                        }
                    }
                }
            }
        }
    }

    private record Ore(Block normal, Block deep, int size, int frequency, int rarity, int minY, int maxY, boolean triangular) {
    }

    public static void ores(Edit edit, Region region, Mask mask, boolean deepBelowZero, boolean deepWhereDeepslate) {
        int bottom = edit.minY();
        int top = edit.maxY();
        int diamondMin = bottom - 80;
        int diamondMax = bottom + 80;
        List<Ore> table = List.of(
                new Ore(Blocks.GRAVEL, null, 33, 14, 100, bottom, top, false),
                new Ore(Blocks.ANDESITE, null, 33, 2, 100, 0, 60, false),
                new Ore(Blocks.ANDESITE, null, 33, 1, 17, 64, 128, false),
                new Ore(Blocks.DIORITE, null, 33, 2, 100, 0, 60, false),
                new Ore(Blocks.DIORITE, null, 33, 1, 17, 64, 128, false),
                new Ore(Blocks.GRANITE, null, 33, 2, 100, 0, 60, false),
                new Ore(Blocks.GRANITE, null, 33, 1, 17, 64, 128, false),
                new Ore(Blocks.TUFF, null, 33, 2, 100, bottom, 0, false),
                new Ore(Blocks.DIRT, null, 33, 7, 100, 0, 160, false),
                new Ore(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 17, 20, 100, 0, 192, true),
                new Ore(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, 17, 30, 100, 136, top, false),
                new Ore(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, 13, 16, 100, -16, 112, true),
                new Ore(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 9, 10, 100, bottom, 72, false),
                new Ore(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 9, 10, 100, -24, 56, true),
                new Ore(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, 9, 90, 100, 80, 384, true),
                new Ore(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 9, 1, 50, -64, -48, false),
                new Ore(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, 9, 4, 100, -64, 32, true),
                new Ore(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 8, 8, 100, -32, 32, true),
                new Ore(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, 8, 4, 100, bottom, 15, false),
                new Ore(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 5, 7, 100, diamondMin, diamondMax, true),
                new Ore(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 8, 2, 100, -64, -4, false),
                new Ore(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 23, 1, 11, diamondMin, diamondMax, true),
                new Ore(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, 10, 4, 100, diamondMin, diamondMax, true),
                new Ore(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 7, 2, 100, -32, 32, true),
                new Ore(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, 7, 4, 100, bottom, 64, false),
                new Ore(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, 5, 100, 100, -16, 480, true));
        for (Ore ore : table) {
            BlockState normal = ore.normal().defaultBlockState();
            BlockState deep = ore.deep() == null ? normal : ore.deep().defaultBlockState();
            Pattern material;
            if (ore.deep() != null && deepBelowZero) {
                material = (x, y, z) -> y < 0 ? deep : normal;
            } else if (ore.deep() != null && deepWhereDeepslate) {
                material = (x, y, z) -> edit.get(x, y, z).is(BlockTags.DEEPSLATE_ORE_REPLACEABLES) ? deep : normal;
            } else {
                material = Pattern.of(normal);
            }
            ore(edit, region, mask, material, ore.size(), ore.frequency(), ore.rarity(), Math.max(bottom, ore.minY()), Math.min(top, ore.maxY()), ore.triangular());
        }
    }

    public record BiomeShape(long timedOut) {
    }

    public static BiomeShape biomeShape(Edit edit, Region region, Holder<Biome> biome, Expression expression, Vec3 zero, Vec3 unit, boolean hollow, int timeout) {
        WorldExprEnvironment environment = new WorldExprEnvironment(edit, unit, zero);
        expression.environment(environment);
        LongAdder timedOut = new LongAdder();
        Ops.parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            if (!inside(environment, expression, region, zero, unit, timeout, timedOut, x, y, z)) {
                return;
            }
            if (hollow && inside(environment, expression, region, zero, unit, timeout, timedOut, x + 1, y, z)
                    && inside(environment, expression, region, zero, unit, timeout, timedOut, x - 1, y, z)
                    && inside(environment, expression, region, zero, unit, timeout, timedOut, x, y + 1, z)
                    && inside(environment, expression, region, zero, unit, timeout, timedOut, x, y - 1, z)
                    && inside(environment, expression, region, zero, unit, timeout, timedOut, x, y, z + 1)
                    && inside(environment, expression, region, zero, unit, timeout, timedOut, x, y, z - 1)) {
                return;
            }
            edit.setBiome(x, y, z, biome);
        }));
        return new BiomeShape(timedOut.sum());
    }

    private static boolean inside(WorldExprEnvironment environment, Expression expression, Region region, Vec3 zero, Vec3 unit, int timeout,
                                  LongAdder timedOut, int x, int y, int z) {
        if (!region.contains(x, y, z)) {
            return false;
        }
        environment.current(x, y, z);
        try {
            return expression.evaluate(new double[]{(x - zero.x()) / unit.x(), (y - zero.y()) / unit.y(), (z - zero.z()) / unit.z()}, timeout) > 0;
        } catch (ExpressionTimeoutException e) {
            timedOut.increment();
            return false;
        }
    }

    private record Placed(BlockPos pos, BlockState state, CompoundTag tile) {
    }

    public static final int UNKNOWN = -1;
    public static final int FAILED = 0;
    public static final int UNOWNED = -2;

    public static int feature(Edit edit, Identifier id, BlockVec at) {
        ServerLevel level = edit.world().level();
        Object feature = WorldGen.features(level.registryAccess()).getValue(id);
        if (feature == null) {
            return UNKNOWN;
        }
        BlockPos origin = new BlockPos(at.x(), at.y(), at.z());
        List<Placed> placed = Sched.join(edit.world().queue().submit(at.x() >> 4, at.z() >> 4, chunk -> {
            if (!owns(edit, at.x() >> 4, at.z() >> 4, at.x() >> 4, at.z() >> 4, 1)) {
                return null;
            }
            BlockStateListPopulator populator = new BlockStateListPopulator(level);
            if (!WorldGen.place(feature, populator, level.getChunkSource().getGenerator(), RandomSource.create(ThreadLocalRandom.current().nextLong()), origin)) {
                return List.of();
            }
            return capture(level, populator);
        }));
        return apply(edit, placed);
    }

    public static int structure(Edit edit, Identifier id, BlockVec at) {
        ServerLevel level = edit.world().level();
        Registry<Structure> registry = WorldGen.structures(level.registryAccess());
        Structure structure = registry.getValue(id);
        if (structure == null) {
            return UNKNOWN;
        }
        List<Placed> placed = Sched.join(edit.world().queue().submit(at.x() >> 4, at.z() >> 4, chunk -> {
            ServerChunkCache source = level.getChunkSource();
            BlockStateListPopulator populator = new BlockStateListPopulator(level);
            StructureStart start = WorldGen.generate(level, registry, structure, new ChunkPos(at.x() >> 4, at.z() >> 4), populator);
            if (!start.isValid()) {
                return List.of();
            }
            BoundingBox box = start.getBoundingBox();
            int minX = SectionPos.blockToSectionCoord(box.minX());
            int minZ = SectionPos.blockToSectionCoord(box.minZ());
            int maxX = SectionPos.blockToSectionCoord(box.maxX());
            int maxZ = SectionPos.blockToSectionCoord(box.maxZ());
            if (!owns(edit, minX, minZ, maxX, maxZ, 0)) {
                return null;
            }
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    start.placeInChunk(populator, level.structureManager(), source.getGenerator(), RandomSource.create(ThreadLocalRandom.current().nextLong()),
                            new BoundingBox(pos.getMinBlockX(), level.getMinY(), pos.getMinBlockZ(), pos.getMaxBlockX(), level.getMaxY(), pos.getMaxBlockZ()), pos);
                }
            }
            return capture(level, populator);
        }));
        return apply(edit, placed);
    }

    public static Registry<?> features(RegistryAccess access) {
        return WorldGen.features(access);
    }

    public static Registry<?> structures(RegistryAccess access) {
        return WorldGen.structures(access);
    }

    private static boolean owns(Edit edit, int minX, int minZ, int maxX, int maxZ, int margin) {
        for (int cx = minX - margin; cx <= maxX + margin; cx++) {
            for (int cz = minZ - margin; cz <= maxZ + margin; cz++) {
                if (!Sched.owns(edit.world().bukkit(), cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<Placed> capture(ServerLevel level, BlockStateListPopulator populator) {
        List<Placed> out = new ArrayList<>();
        for (CraftBlockState state : populator.getSnapshotBlocks()) {
            BlockPos pos = state.getPosition();
            BlockEntity entity = populator.getBlockEntity(pos);
            out.add(new Placed(pos, state.getHandle(), entity == null ? null : entity.saveWithoutMetadata(level.registryAccess())));
        }
        return out;
    }

    private static int apply(Edit edit, List<Placed> placed) {
        if (placed == null) {
            return UNOWNED;
        }
        int count = 0;
        for (Placed block : placed) {
            if (edit.set(block.pos().getX(), block.pos().getY(), block.pos().getZ(), block.state(), block.tile())) {
                count++;
            }
        }
        return count;
    }

    public static CuboidRegion around(Edit edit, BlockVec center, int size) {
        return UtilityOps.clampY(edit, center.subtract(size, size, size), center.add(size, size, size));
    }
}
