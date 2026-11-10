package teacommontea.kawe.session;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.parse.Masks;

import java.util.function.Predicate;

public final class Navigation {

    public static final int VERTICAL_HEIGHT = 256;

    public record Target(double x, double y, double z) {
        static Target at(int x, int y, int z) {
            return new Target(x + 0.5, y, z + 0.5);
        }
    }

    private Navigation() {
    }

    public static void teleport(Player player, World world, Target target, float yaw, float pitch) {
        Location to = new Location(world, target.x(), target.y(), target.z(), yaw, pitch);
        Sched.entity(player, () -> {
            if (player.getWorld().equals(world)) {
                player.teleportAsync(to);
            }
        });
    }

    private static boolean blocks(Edit world, int x, int y, int z) {
        return y >= world.minY() && y <= world.maxY() && Masks.blocksMovement(world.get(x, y, z));
    }

    private static boolean harmful(Edit world, int x, int y, int z) {
        BlockState state = world.get(x, y, z);
        return Masks.blocksMovement(state) || state.getBlock() == Blocks.LAVA || state.is(BlockTags.FIRE);
    }

    private static boolean standable(Edit world, int x, int y, int z) {
        return !harmful(world, x, y + 1, z) && !harmful(world, x, y, z) && blocks(world, x, y - 1, z);
    }

    public static Target free(Edit world, BlockVec from) {
        int x = from.x();
        int z = from.z();
        int y = Math.max(world.minY(), from.y());
        int origin = y;
        int top = Math.min(world.maxY(), y + VERTICAL_HEIGHT) + 2;
        int free = 0;
        for (; y <= top; y++) {
            free = blocks(world, x, y, z) ? 0 : free + 1;
            if (free == 2) {
                return y - 1 == origin ? null : Target.at(x, y - 1, z);
            }
        }
        return null;
    }

    public static Target ascend(Edit world, BlockVec from) {
        int x = from.x();
        int z = from.z();
        int y = Math.max(world.minY(), from.y() + 1);
        int top = Math.min(world.maxY(), y + VERTICAL_HEIGHT) + 2;
        for (; y <= top; y++) {
            if (standable(world, x, y, z)) {
                return Target.at(x, y, z);
            }
        }
        return null;
    }

    public static Target descend(Edit world, BlockVec from) {
        int x = from.x();
        int z = from.z();
        int y = Math.max(world.minY(), from.y() - 1);
        int bottom = Math.max(world.minY() + 1, y - VERTICAL_HEIGHT);
        for (; y >= bottom; y--) {
            if (standable(world, x, y, z)) {
                return Target.at(x, y, z);
            }
        }
        return null;
    }

    public static Integer ceiling(Edit world, BlockVec from, int clearance) {
        int x = from.x();
        int z = from.z();
        int initial = Math.max(world.minY(), from.y());
        int y = Math.max(world.minY(), from.y() + 2);
        if (!world.get(x, y, z).isAir()) {
            return null;
        }
        int top = Math.min(world.maxY(), y + VERTICAL_HEIGHT);
        for (; y <= top; y++) {
            if (blocks(world, x, y, z)) {
                int platform = y - 3 - clearance;
                return platform <= initial ? null : platform + 1;
            }
        }
        return null;
    }

    public static Integer upwards(Edit world, BlockVec from, int distance) {
        int x = from.x();
        int z = from.z();
        int initial = Math.max(world.minY(), from.y());
        int y = Math.max(world.minY(), from.y() + 1);
        int stop = (int) Math.min(world.maxY() + 1L, (long) initial + distance);
        for (; y <= world.maxY() + 2; y++) {
            if (blocks(world, x, y, z) || y > stop + 1) {
                return null;
            }
            if (y == stop + 1) {
                return y - 1;
            }
        }
        return null;
    }

    public static Target onGround(Edit world, BlockVec from) {
        int x = from.x();
        int z = from.z();
        int y = Math.max(world.minY(), from.y());
        int bottom = Math.max(world.minY(), y - VERTICAL_HEIGHT);
        for (; y >= bottom; y--) {
            if (blocks(world, x, y, z)) {
                return Target.at(x, y + 1, z);
            }
        }
        return null;
    }

    public static BlockVec solidTrace(Edit world, Vec3 eye, float yaw, float pitch, int range) {
        return trace(world, eye, yaw, pitch, range, Masks::solid);
    }

    public static BlockVec blockTrace(Edit world, Vec3 eye, float yaw, float pitch, int range) {
        return trace(world, eye, yaw, pitch, range, state -> !state.isAir());
    }

    private static BlockVec trace(Edit world, Vec3 eye, float yaw, float pitch, int range, Predicate<BlockState> stop) {
        Ray ray = new Ray(eye, yaw, pitch, range);
        do {
            if (ray.y >= world.minY() && ray.y <= world.maxY() && stop.test(world.get(ray.x, ray.y, ray.z))) {
                return new BlockVec(ray.x, ray.y, ray.z);
            }
        } while (ray.advance());
        return null;
    }

    public static BlockVec maskedTrace(Edit world, Vec3 eye, float yaw, float pitch, int range, Mask stop, boolean lastBlock, boolean face) {
        Ray ray = new Ray(eye, yaw, pitch, range);
        BlockVec previous = null;
        BlockVec last = null;
        do {
            if (ray.y >= world.minY() && ray.y <= world.maxY()) {
                if (stop.test(ray.x, ray.y, ray.z)) {
                    return face && previous != null ? previous : new BlockVec(ray.x, ray.y, ray.z);
                }
                last = new BlockVec(ray.x, ray.y, ray.z);
            }
            previous = new BlockVec(ray.x, ray.y, ray.z);
        } while (ray.advance());
        return lastBlock ? last : null;
    }

    public static Target thru(Edit world, Vec3 eye, float yaw, float pitch, int range) {
        Ray ray = new Ray(eye, yaw, pitch, range);
        while (!blocks(world, ray.x, ray.y, ray.z)) {
            if (!ray.advance()) {
                return null;
            }
        }
        while (blocks(world, ray.x, ray.y, ray.z)) {
            if (!ray.advance()) {
                return null;
            }
        }
        return onGround(world, new BlockVec(ray.x, ray.y, ray.z));
    }

    private static final class Ray {
        private int x;
        private int y;
        private int z;
        private final int stepX;
        private final int stepY;
        private final int stepZ;
        private final double deltaX;
        private final double deltaY;
        private final double deltaZ;
        private double maxX;
        private double maxY;
        private double maxZ;
        private final double range;

        Ray(Vec3 eye, float yaw, float pitch, double range) {
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);
            double dx = -Math.sin(yawRad) * Math.cos(pitchRad);
            double dy = -Math.sin(pitchRad);
            double dz = Math.cos(yawRad) * Math.cos(pitchRad);
            this.range = range;
            x = (int) Math.floor(eye.x());
            y = (int) Math.floor(eye.y());
            z = (int) Math.floor(eye.z());
            stepX = (int) Math.signum(dx);
            stepY = (int) Math.signum(dy);
            stepZ = (int) Math.signum(dz);
            deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
            deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
            deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
            maxX = boundary(eye.x(), x, stepX, deltaX);
            maxY = boundary(eye.y(), y, stepY, deltaY);
            maxZ = boundary(eye.z(), z, stepZ, deltaZ);
        }

        private static double boundary(double origin, int cell, int step, double delta) {
            if (step == 0) {
                return Double.POSITIVE_INFINITY;
            }
            double distance = step > 0 ? cell + 1 - origin : origin - cell;
            return distance * delta;
        }

        boolean advance() {
            double t;
            if (maxX <= maxY && maxX <= maxZ) {
                t = maxX;
                x += stepX;
                maxX += deltaX;
            } else if (maxY <= maxZ) {
                t = maxY;
                y += stepY;
                maxY += deltaY;
            } else {
                t = maxZ;
                z += stepZ;
                maxZ += deltaZ;
            }
            return t <= range;
        }
    }
}
