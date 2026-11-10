package teacommontea.kawe.op;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionTimeoutException;
import teacommontea.kawe.expr.WorldExprEnvironment;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.schematic.Legacy;

import java.util.concurrent.atomic.LongAdder;

public final class Shapes {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public record Generated(long changed, long timedOut) {
    }

    private Shapes() {
    }

    private static long fill(Edit edit, int x0, int y0, int z0, int x1, int y1, int z1, Pattern pattern, Mask shape) {
        int low = Math.max(y0, edit.minY());
        int high = Math.min(y1, edit.maxY());
        if (low > high || x0 > x1 || z0 > z1) {
            return 0;
        }
        return Ops.replaceBlocks(edit, new CuboidRegion(new BlockVec(x0, low, z0), new BlockVec(x1, high, z1)), shape, pattern);
    }

    public static long sphere(Edit edit, BlockVec center, Pattern pattern, double radiusX, double radiusY, double radiusZ, boolean filled) {
        double rx = radiusX + 0.5;
        double ry = radiusY + 0.5;
        double rz = radiusZ + 0.5;
        double invX = 1 / rx;
        double invY = 1 / ry;
        double invZ = 1 / rz;
        int cx = center.x();
        int cy = center.y();
        int cz = center.z();
        int ceilX = (int) Math.ceil(rx);
        int ceilY = (int) Math.ceil(ry);
        int ceilZ = (int) Math.ceil(rz);
        Mask shape = (x, y, z) -> {
            int dx = Math.abs(x - cx);
            int dy = Math.abs(y - cy);
            int dz = Math.abs(z - cz);
            double xn = dx * invX;
            double yn = dy * invY;
            double zn = dz * invZ;
            double sx = xn * xn;
            double sy = yn * yn;
            double sz = zn * zn;
            if (sx + sy + sz > 1) {
                return false;
            }
            if (filled) {
                return true;
            }
            double nx = (dx + 1) * invX;
            double ny = (dy + 1) * invY;
            double nz = (dz + 1) * invZ;
            return nx * nx + sy + sz > 1 || ny * ny + sx + sz > 1 || nz * nz + sx + sy > 1;
        };
        return fill(edit, cx - ceilX, cy - ceilY, cz - ceilZ, cx + ceilX, cy + ceilY, cz + ceilZ, pattern, shape);
    }

    public static long cylinder(Edit edit, BlockVec base, Pattern pattern, double radiusX, double radiusZ, int height, double thickness, boolean filled) {
        if (height == 0) {
            return 0;
        }
        long low = height < 0 ? (long) base.y() + height + 1 : base.y();
        long high = height < 0 ? base.y() : (long) base.y() + height - 1;
        low = Math.max(low, edit.minY());
        high = Math.min(high, edit.maxY());
        if (low > high) {
            return 0;
        }
        int py = (int) low;
        int layers = (int) (high - low + 1);
        double rx = radiusX + 0.5;
        double rz = radiusZ + 0.5;
        double invX = 1 / rx;
        double invZ = 1 / rz;
        double minInvX = 1 / (rx - thickness);
        double minInvZ = 1 / (rz - thickness);
        int cx = base.x();
        int cz = base.z();
        int ceilX = (int) Math.ceil(rx);
        int ceilZ = (int) Math.ceil(rz);
        Mask shape = (x, y, z) -> {
            int dx = Math.abs(x - cx);
            int dz = Math.abs(z - cz);
            double xn = dx * invX;
            double zn = dz * invZ;
            if (xn * xn + zn * zn > 1) {
                return false;
            }
            if (thickness != 0) {
                double ix = dx * minInvX;
                double iz = dz * minInvZ;
                double nx = (dx + 1) * minInvX;
                double nz = (dz + 1) * minInvZ;
                return iz * iz + nx * nx > 1 || nz * nz + ix * ix > 1;
            }
            if (filled) {
                return true;
            }
            double nx = (dx + 1) * invX;
            double nz = (dz + 1) * invZ;
            return zn * zn + nx * nx > 1 || nz * nz + xn * xn > 1;
        };
        return fill(edit, cx - ceilX, py, cz - ceilZ, cx + ceilX, py + layers - 1, cz + ceilZ, pattern, shape);
    }

    public static long cone(Edit edit, BlockVec base, Pattern pattern, double radiusX, double radiusZ, int height, boolean filled, double thickness) {
        if (height == 0 || height == Integer.MIN_VALUE) {
            return 0;
        }
        int layers = Math.abs(height);
        double radiusXPow = radiusX * radiusX;
        double radiusZPow = radiusZ * radiusZ;
        double heightPow = (double) height * height;
        int cx = base.x();
        int cy = base.y();
        int cz = base.z();
        boolean down = height < 0;
        Mask shape = (x, y, z) -> {
            int layer = down ? cy - y : y - cy;
            double dx = x - cx;
            double dz = z - cz;
            double xs = dx * dx / radiusXPow;
            double zs = dz * dz / radiusZPow;
            double ys = (double) (layer - layers) * (layer - layers) / heightPow;
            if (xs + zs - ys > 0) {
                return false;
            }
            if (filled || layer + thickness >= layers) {
                return true;
            }
            double ax = Math.abs(dx) + thickness;
            double az = Math.abs(dz) + thickness;
            double above = layer + thickness - layers;
            boolean xInside = ax * ax / radiusXPow + zs - ys <= 0;
            boolean zInside = xs + az * az / radiusZPow - ys <= 0;
            boolean yInside = xs + zs - above * above / heightPow <= 0;
            return !(xInside && zInside && yInside);
        };
        int ceilX = (int) Math.ceil(radiusX);
        int ceilZ = (int) Math.ceil(radiusZ);
        int y0 = (int) Math.max(edit.minY(), down ? (long) cy - layers + 1 : cy);
        int y1 = (int) Math.min(edit.maxY(), down ? cy : (long) cy + layers - 1);
        if (y0 > y1) {
            return 0;
        }
        return fill(edit, cx - ceilX, y0, cz - ceilZ, cx + ceilX, y1, cz + ceilZ, pattern, shape);
    }

    public static long pyramid(Edit edit, BlockVec base, Pattern pattern, int size, boolean filled) {
        if (size <= 0) {
            return 0;
        }
        int bx = base.x();
        int by = base.y();
        int bz = base.z();
        Mask shape = (x, y, z) -> {
            int extent = size - 1 - (y - by);
            int ax = Math.abs(x - bx);
            int az = Math.abs(z - bz);
            if (ax > extent || az > extent) {
                return false;
            }
            return filled || ax == extent || az == extent;
        };
        return fill(edit, bx - size + 1, by, bz - size + 1, bx + size - 1, by + size - 1, bz + size - 1, pattern, shape);
    }

    private static final class ShapeEval {
        private final Edit edit;
        private final Pattern pattern;
        private final Expression expression;
        private final WorldExprEnvironment environment;
        private final Vec3 zero;
        private final Vec3 unit;
        private final int timeout;
        private final LongAdder timedOut;

        ShapeEval(Edit edit, Pattern pattern, Expression expression, Vec3 zero, Vec3 unit, int timeout, LongAdder timedOut) {
            this.edit = edit;
            this.pattern = pattern;
            this.expression = expression;
            this.environment = new WorldExprEnvironment(edit, unit, zero);
            this.zero = zero;
            this.unit = unit;
            this.timeout = timeout;
            this.timedOut = timedOut;
            expression.environment(environment);
        }

        boolean inside(int x, int y, int z, BlockState base) {
            environment.current(x, y, z);
            int legacy = Legacy.combined(base);
            try {
                return expression.evaluate(new double[]{(x - zero.x()) / unit.x(), (y - zero.y()) / unit.y(), (z - zero.z()) / unit.z(),
                        legacy >> 4, legacy & 0xF}, timeout) > 0;
            } catch (ExpressionTimeoutException e) {
                timedOut.increment();
                return false;
            }
        }

        boolean inside(int x, int y, int z) {
            BlockState base = pattern.pick(x, y, z).state(x, y, z);
            return inside(x, y, z, base == null ? AIR : base);
        }

        boolean place(int x, int y, int z, boolean hollow, Cache cache, Region region) {
            Pattern picked = pattern.pick(x, y, z);
            BlockState base = picked.state(x, y, z);
            BlockState probe = base == null ? AIR : base;
            int legacy = Legacy.combined(probe);
            if (!inside(x, y, z, probe)) {
                cache.mark(x, y, z, false);
                return false;
            }
            int type = (int) expression.read("type");
            int data = (int) expression.read("data");
            cache.mark(x, y, z, true);
            if (hollow && cache.inside(region, x + 1, y, z) && cache.inside(region, x - 1, y, z) && cache.inside(region, x, y, z + 1)
                    && cache.inside(region, x, y, z - 1) && cache.inside(region, x, y + 1, z) && cache.inside(region, x, y - 1, z)) {
                return false;
            }
            if (type != legacy >> 4 || data != (legacy & 0xF)) {
                BlockState changed = Legacy.state(type, data);
                if (changed != null) {
                    return edit.set(x, y, z, changed);
                }
            }
            return base != null && edit.set(x, y, z, base, picked.tile(x, y, z));
        }

        final class Cache {
            private final int x0;
            private final int y0;
            private final int z0;
            private final int sizeY;
            private final int sizeZ;
            private final byte[] states;

            Cache(int x0, int y0, int z0, int x1, int y1, int z1) {
                this.x0 = x0 - 1;
                this.y0 = y0 - 1;
                this.z0 = z0 - 1;
                this.sizeY = y1 - y0 + 3;
                this.sizeZ = z1 - z0 + 3;
                this.states = new byte[(x1 - x0 + 3) * sizeY * sizeZ];
            }

            private int index(int x, int y, int z) {
                return (y - y0) + (z - z0) * sizeY + (x - x0) * sizeY * sizeZ;
            }

            void mark(int x, int y, int z, boolean inside) {
                states[index(x, y, z)] = (byte) (inside ? 1 : -1);
            }

            boolean inside(Region region, int x, int y, int z) {
                if (!region.contains(x, y, z)) {
                    return false;
                }
                int index = index(x, y, z);
                byte state = states[index];
                if (state == 0) {
                    boolean result = ShapeEval.this.inside(x, y, z);
                    states[index] = (byte) (result ? 1 : -1);
                    return result;
                }
                return state > 0;
            }
        }
    }

    public static Generated generate(Edit edit, Region region, Pattern pattern, Expression expression, Vec3 zero, Vec3 unit, boolean hollow, int timeout) {
        LongAdder count = new LongAdder();
        LongAdder timedOut = new LongAdder();
        ShapeEval eval = new ShapeEval(edit, pattern, expression, zero, unit, timeout, timedOut);
        BlockVec min = region.min();
        BlockVec max = region.max();
        Ops.parallelChunks(edit, region, key -> {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            ShapeEval.Cache cache = eval.new Cache(Math.max(min.x(), cx << 4), min.y(), Math.max(min.z(), cz << 4),
                    Math.min(max.x(), (cx << 4) + 15), max.y(), Math.min(max.z(), (cz << 4) + 15));
            region.forEachInChunk(cx, cz, (x, y, z) -> {
                if (eval.place(x, y, z, hollow, cache, region)) {
                    count.increment();
                }
            });
        });
        return new Generated(count.sum(), timedOut.sum());
    }

    public static Generated deform(Edit edit, Region region, Expression expression, Vec3 zero, Vec3 unit, int timeout) {
        edit.captureTiles();
        WorldExprEnvironment environment = new WorldExprEnvironment(edit, unit, zero);
        expression.environment(environment);
        Vec3 offset = zero.add(0.5, 0.5, 0.5);
        int minY = edit.minY();
        int maxY = edit.maxY();
        LongAdder count = new LongAdder();
        LongAdder timedOut = new LongAdder();
        Ops.parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            environment.current(x, y, z);
            try {
                expression.evaluate(new double[]{(x - zero.x()) / unit.x(), (y - zero.y()) / unit.y(), (z - zero.z()) / unit.z()}, timeout);
            } catch (ExpressionTimeoutException e) {
                timedOut.increment();
                return;
            }
            int sx = (int) Math.floor(expression.read("x") * unit.x() + offset.x());
            int sy = (int) Math.floor(expression.read("y") * unit.y() + offset.y());
            int sz = (int) Math.floor(expression.read("z") * unit.z() + offset.z());
            BlockState state = AIR;
            CompoundTag tile = null;
            if (sy >= minY && sy <= maxY) {
                state = edit.original(sx, sy, sz);
                tile = state.hasBlockEntity() ? edit.originalTile(sx, sy, sz) : null;
            }
            if (edit.set(x, y, z, state, tile)) {
                count.increment();
            }
        }));
        return new Generated(count.sum(), timedOut.sum());
    }
}
