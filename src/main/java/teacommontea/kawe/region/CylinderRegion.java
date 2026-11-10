package teacommontea.kawe.region;

import teacommontea.kawe.math.BlockVec2;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class CylinderRegion implements Region {

    private BlockVec2 center;
    private double radiusX;
    private double radiusZ;
    private double invX;
    private double invZ;
    private int minY;
    private int maxY;
    private boolean hasY;

    public CylinderRegion() {
        this.center = new BlockVec2(0, 0);
        radius(0, 0);
    }

    public CylinderRegion(BlockVec center, double radiusX, double radiusZ, int minY, int maxY) {
        this.center = new BlockVec2(center.x(), center.z());
        radius(radiusX, radiusZ);
        this.minY = minY;
        this.maxY = maxY;
        this.hasY = true;
    }

    public BlockVec2 centerColumn() {
        return center;
    }

    public void center(BlockVec2 value) {
        this.center = value;
    }

    public double radiusX() {
        return radiusX - 0.5;
    }

    public double radiusZ() {
        return radiusZ - 0.5;
    }

    public void radius(double x, double z) {
        this.radiusX = x + 0.5;
        this.radiusZ = z + 0.5;
        this.invX = 1 / radiusX;
        this.invZ = 1 / radiusZ;
    }

    public void extendRadius(double x, double z) {
        radius(Math.max(x, radiusX()), Math.max(z, radiusZ()));
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public void minY(int y) {
        hasY = true;
        minY = y;
    }

    public void maxY(int y) {
        hasY = true;
        maxY = y;
    }

    public boolean setY(int y) {
        if (!hasY) {
            minY = y;
            maxY = y;
            hasY = true;
            return true;
        }
        if (y < minY) {
            minY = y;
            return true;
        }
        if (y > maxY) {
            maxY = y;
            return true;
        }
        return false;
    }

    @Override
    public Vec3 center() {
        return new Vec3(center.x(), (maxY + minY) / 2, center.z());
    }

    @Override
    public BlockVec min() {
        return BlockVec.at(center.x() - radiusX(), minY, center.z() - radiusZ());
    }

    @Override
    public BlockVec max() {
        return BlockVec.at(center.x() + radiusX(), maxY, center.z() + radiusZ());
    }

    @Override
    public long volume() {
        return BigDecimal.valueOf(radiusX)
                .multiply(BigDecimal.valueOf(radiusZ))
                .multiply(BigDecimal.valueOf(Math.PI))
                .multiply(BigDecimal.valueOf(height()))
                .setScale(0, RoundingMode.FLOOR)
                .longValue();
    }

    @Override
    public int width() {
        return (int) (2 * radiusX);
    }

    @Override
    public int height() {
        return maxY - minY + 1;
    }

    @Override
    public int length() {
        return (int) (2 * radiusZ);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return y >= minY && y <= maxY && containsFlat(x, z);
    }

    @Override
    public boolean containsColumn(int x, int z) {
        return containsFlat(x, z);
    }

    public boolean containsFlat(int x, int z) {
        double dx = Math.abs(x - center.x()) * invX;
        double dz = Math.abs(z - center.z()) * invZ;
        return dx * dx + dz * dz <= 1;
    }

    private BlockVec2 diff(BlockVec... changes) throws RegionOperationException {
        int x = 0;
        int z = 0;
        for (BlockVec change : changes) {
            x += change.x();
            z += change.z();
        }
        if ((x & 1) + (z & 1) != 0) {
            throw new RegionOperationException("kawe.selection.cylinder.error.even-horizontal");
        }
        return new BlockVec2(Math.floorDiv(x, 2), Math.floorDiv(z, 2));
    }

    private static BlockVec2 changes(BlockVec... changes) {
        int x = 0;
        int z = 0;
        for (BlockVec change : changes) {
            x += Math.abs(change.x());
            z += Math.abs(change.z());
        }
        return new BlockVec2(Math.floorDiv(x, 2), Math.floorDiv(z, 2));
    }

    @Override
    public void expand(BlockVec... changes) throws RegionOperationException {
        BlockVec2 d = diff(changes);
        center = center.add(d.x(), d.z());
        BlockVec2 c = changes(changes);
        radiusX += c.x();
        radiusZ += c.z();
        invX = 1 / radiusX;
        invZ = 1 / radiusZ;
        for (BlockVec change : changes) {
            if (change.y() > 0) {
                maxY += change.y();
            } else {
                minY += change.y();
            }
        }
    }

    @Override
    public void contract(BlockVec... changes) throws RegionOperationException {
        BlockVec2 d = diff(changes);
        center = center.add(-d.x(), -d.z());
        BlockVec2 c = changes(changes);
        radiusX = Math.max(1.5, radiusX - c.x());
        radiusZ = Math.max(1.5, radiusZ - c.z());
        invX = 1 / radiusX;
        invZ = 1 / radiusZ;
        for (BlockVec change : changes) {
            int height = maxY - minY;
            if (change.y() > 0) {
                minY += Math.min(height, change.y());
            } else {
                maxY += Math.max(-height, change.y());
            }
        }
    }

    @Override
    public void shift(BlockVec change) {
        center = center.add(change.x(), change.z());
        maxY += change.y();
        minY += change.y();
    }

    @Override
    public CylinderRegion copy() {
        CylinderRegion copy = new CylinderRegion(new BlockVec(center.x(), 0, center.z()), radiusX(), radiusZ(), minY, maxY);
        copy.hasY = hasY;
        return copy;
    }

    @Override
    public List<BlockVec2> polygonize(int maxPoints) {
        int points = (int) Math.ceil(Math.PI * 2 * Math.max(radiusX, radiusZ));
        if (maxPoints >= 0 && points > maxPoints) {
            points = maxPoints;
        }
        List<BlockVec2> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double angle = i * (2.0 * Math.PI) / points;
            out.add(new BlockVec2((int) Math.round(center.x() + Math.cos(angle) * radiusX), (int) Math.round(center.z() + Math.sin(angle) * radiusZ)));
        }
        return out;
    }

    @Override
    public String toString() {
        return center + " - (" + radiusX + ", " + radiusZ + ")(" + minY + ", " + maxY + ")";
    }
}
