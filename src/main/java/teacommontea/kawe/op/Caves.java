package teacommontea.kawe.op;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.region.Region;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class Caves {

    public record Settings(int size, int frequency, int rarity, int minY, int maxY, int systemFrequency, int individualRarity,
                           int pocketChance, int pocketMin, int pocketMax) {
    }

    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState CAVE_AIR = Blocks.CAVE_AIR.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    private final Edit edit;
    private final Region region;
    private final Settings settings;
    private final int bottom;
    private final int top;

    private Caves(Edit edit, Region region, Settings settings) {
        this.edit = edit;
        this.region = region;
        this.settings = settings;
        this.bottom = edit.minY();
        this.top = edit.maxY();
    }

    public static void carve(Edit edit, Region region, Settings settings) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long seed = random.nextLong();
        long mixX = random.nextLong() | 1;
        long mixZ = random.nextLong() | 1;
        Caves caves = new Caves(edit, region, settings);
        int reach = settings.size();
        Ops.parallelChunks(edit, region, key -> {
            int originX = ChunkKey.x(key);
            int originZ = ChunkKey.z(key);
            for (int sx = originX - reach; sx <= originX + reach; sx++) {
                for (int sz = originZ - reach; sz <= originZ + reach; sz++) {
                    caves.source(new Random(sx * mixX ^ sz * mixZ ^ seed), sx, sz, originX, originZ);
                }
            }
        });
    }

    private void source(Random random, int chunkX, int chunkZ, int originX, int originZ) {
        int count = random.nextInt(random.nextInt(random.nextInt(settings.frequency()) + 1) + 1);
        if (random.nextInt(100) >= settings.rarity()) {
            count = 0;
        }
        for (int j = 0; j < count; j++) {
            double x = (chunkX << 4) + random.nextInt(16);
            double y = random.nextInt(random.nextInt(settings.maxY() - settings.minY() + 1) + 1) + settings.minY();
            double z = (chunkZ << 4) + random.nextInt(16);
            int nodes = settings.systemFrequency();
            boolean large = false;
            if (random.nextInt(100) <= settings.individualRarity()) {
                node(random.nextLong(), originX, originZ, x, y, z, 1.0 + random.nextDouble() * 6.0, 0, 0, -1, -1, 0.5);
                large = true;
            }
            if (large || random.nextInt(100) <= settings.pocketChance() - 1) {
                nodes += settings.pocketMax() > settings.pocketMin() ? random.nextInt(settings.pocketMin(), settings.pocketMax()) : settings.pocketMin();
            }
            while (nodes-- > 0) {
                double yaw = random.nextDouble() * Math.PI * 2.0;
                double pitch = (random.nextDouble() - 0.5) * 2.0 / 8.0;
                double width = random.nextDouble() * 2.0 + random.nextDouble();
                node(random.nextLong(), originX, originZ, x, y, z, width, yaw, pitch, 0, 0, 1.0);
            }
        }
    }

    private void node(long seed, int originX, int originZ, double x, double y, double z, double width, double yaw, double pitch,
                      int angle, int maxAngle, double squash) {
        Random random = new Random(seed);
        int bx = originX << 4;
        int bz = originZ << 4;
        double centerX = bx + 7;
        double centerZ = bz + 7;
        double yawDrift = 0;
        double pitchDrift = 0;
        if (maxAngle <= 0) {
            int area = settings.size() * 16 - 16;
            maxAngle = area - random.nextInt(area / 4);
        }
        boolean large = false;
        if (angle == -1) {
            angle = maxAngle / 2;
            large = true;
        }
        int branch = random.nextInt(maxAngle / 2) + maxAngle / 4;
        boolean steep = random.nextInt(6) == 0;
        for (; angle < maxAngle; angle++) {
            double radius = 1.5 + Math.sin(angle * Math.PI / maxAngle) * width;
            double radiusY = radius * squash;
            double cosPitch = Math.cos(pitch);
            x += Math.cos(yaw) * cosPitch;
            y += Math.sin(pitch);
            z += Math.sin(yaw) * cosPitch;
            pitch *= steep ? 0.92 : 0.7;
            pitch += pitchDrift * 0.1;
            yaw += yawDrift * 0.1;
            pitchDrift *= 0.9;
            yawDrift *= 0.75;
            pitchDrift += (random.nextDouble() - random.nextDouble()) * random.nextDouble() * 2.0;
            yawDrift += (random.nextDouble() - random.nextDouble()) * random.nextDouble() * 4.0;
            if (!large && angle == branch && width > 1.0 && maxAngle > 0) {
                node(random.nextLong(), originX, originZ, x, y, z, random.nextDouble() * 0.5 + 0.5, yaw - Math.PI / 2, pitch / 3.0, angle, maxAngle, 1.0);
                node(random.nextLong(), originX, originZ, x, y, z, random.nextDouble() * 0.5 + 0.5, yaw + Math.PI / 2, pitch / 3.0, angle, maxAngle, 1.0);
                return;
            }
            if (!large && random.nextInt(4) == 0) {
                continue;
            }
            double dx = x - centerX;
            double dz = z - centerZ;
            double left = maxAngle - angle;
            double limit = width + 2.0 + 16.0;
            if (dx * dx + dz * dz - left * left > limit * limit) {
                return;
            }
            if (x < centerX - 16.0 - radius * 2.0 || z < centerZ - 16.0 - radius * 2.0
                    || x > centerX + 16.0 + radius * 2.0 || z > centerZ + 16.0 + radius * 2.0) {
                continue;
            }
            int x0 = Math.max(0, (int) Math.floor(x - radius) - bx - 1);
            int x1 = Math.min(16, (int) Math.floor(x + radius) - bx + 1);
            int y0 = (int) Math.floor(y - radiusY) - 1;
            int y1 = (int) Math.floor(y + radiusY) + 1;
            int z0 = Math.max(0, (int) Math.floor(z - radius) - bz - 1);
            int z1 = Math.min(16, (int) Math.floor(z + radius) - bz + 1);
            if (y0 < bottom + 1) {
                y0 = bottom;
            }
            if (y1 >= top - 8) {
                y1 = top - 8;
            }
            if (water(bx, bz, x0, x1, y0, y1, z0, z1)) {
                continue;
            }
            for (int lx = x0; lx < x1; lx++) {
                double nx = (lx + bx + 0.5 - x) / radius;
                for (int lz = z0; lz < z1; lz++) {
                    double nz = (lz + bz + 0.5 - z) / radius;
                    if (nx * nx + nz * nz >= 1.0) {
                        continue;
                    }
                    boolean grass = false;
                    for (int ly = y1; ly > y0; ly--) {
                        double ny = (ly - 1 + 0.5 - y) / radiusY;
                        if (ny <= -0.7 || nx * nx + ny * ny + nz * nz >= 1.0) {
                            continue;
                        }
                        int wx = bx + lx;
                        int wz = bz + lz;
                        Block block = edit.get(wx, ly, wz).getBlock();
                        if (block == Blocks.MYCELIUM || block == Blocks.GRASS_BLOCK) {
                            grass = true;
                        }
                        if (!carvable(block) || !region.contains(wx, ly, wz)) {
                            continue;
                        }
                        if (ly - 1 < bottom + 10) {
                            edit.set(wx, ly, wz, LAVA);
                            continue;
                        }
                        edit.set(wx, ly, wz, CAVE_AIR);
                        if (grass && edit.get(wx, ly - 1, wz).getBlock() == Blocks.DIRT && region.contains(wx, ly - 1, wz)) {
                            edit.set(wx, ly - 1, wz, STONE);
                        }
                    }
                }
            }
            if (large) {
                break;
            }
        }
    }

    private boolean water(int bx, int bz, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int lx = x0; lx < x1; lx++) {
            for (int lz = z0; lz < z1; lz++) {
                for (int ly = y1 + 1; ly >= y0 - 1; ly--) {
                    if (ly < top && ly >= bottom && edit.get(bx + lx, ly, bz + lz).getBlock() == Blocks.WATER) {
                        return true;
                    }
                    if (ly != y0 - 1 && lx != x0 && lx != x1 - 1 && lz != z0 && lz != z1 - 1) {
                        ly = y0;
                    }
                }
            }
        }
        return false;
    }

    private static boolean carvable(Block block) {
        return block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR && block != Blocks.WATER
                && block != Blocks.LAVA && block != Blocks.BEDROCK;
    }
}
