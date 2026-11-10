package teacommontea.kawe.region;

import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class EllipsoidRegion implements Region {

    private static final BigDecimal BASE = BigDecimal.valueOf((4.0 / 3.0) * Math.PI);

    private BlockVec center;
    private Vec3 radius;
    private double invX;
    private double invY;
    private double invZ;

    public EllipsoidRegion(BlockVec center, Vec3 radius) {
        this.center = center;
        radius(radius);
    }

    public BlockVec centerBlock() {
        return center;
    }

    public void center(BlockVec value) {
        this.center = value;
    }

    public Vec3 radius() {
        return radius;
    }

    public void radius(Vec3 value) {
        this.radius = value;
        double rx = value.x() + 0.5;
        double ry = value.y() + 0.5;
        double rz = value.z() + 0.5;
        invX = 1 / (rx * rx);
        invY = 1 / (ry * ry);
        invZ = 1 / (rz * rz);
    }

    public void extendRadius(Vec3 minRadius) {
        radius(minRadius.max(radius()));
    }

    @Override
    public Vec3 center() {
        return center.toVec3();
    }

    @Override
    public BlockVec min() {
        return center.subtract(reach());
    }

    @Override
    public BlockVec max() {
        return center.add(reach());
    }

    private BlockVec reach() {
        return new BlockVec((int) Math.floor(radius.x() + 0.5), (int) Math.floor(radius.y() + 0.5), (int) Math.floor(radius.z() + 0.5));
    }

    @Override
    public long volume() {
        return BASE.multiply(BigDecimal.valueOf(radius.x() + 0.5))
                .multiply(BigDecimal.valueOf(radius.y() + 0.5))
                .multiply(BigDecimal.valueOf(radius.z() + 0.5))
                .setScale(0, RoundingMode.FLOOR)
                .longValue();
    }

    @Override
    public int width() {
        return (int) (2 * (radius.x() + 0.5));
    }

    @Override
    public int height() {
        return (int) (2 * (radius.y() + 0.5));
    }

    @Override
    public int length() {
        return (int) (2 * (radius.z() + 0.5));
    }

    @Override
    public boolean contains(int x, int y, int z) {
        double cx = x - center.x();
        double cy = y - center.y();
        double cz = z - center.z();
        return cx * cx * invX + cy * cy * invY + cz * cz * invZ <= 1;
    }

    private BlockVec diff(BlockVec... changes) throws RegionOperationException {
        BlockVec sum = BlockVec.ZERO;
        for (BlockVec change : changes) {
            sum = sum.add(change);
        }
        if ((sum.x() & 1) + (sum.y() & 1) + (sum.z() & 1) != 0) {
            throw new RegionOperationException("kawe.selection.ellipsoid.error.even-horizontal");
        }
        return sum.divide(2);
    }

    private static Vec3 changes(BlockVec... changes) {
        Vec3 total = Vec3.ZERO;
        for (BlockVec change : changes) {
            total = total.add(change.abs().toVec3());
        }
        Vec3 half = total.divide(2);
        return new Vec3(Math.floor(half.x()), Math.floor(half.y()), Math.floor(half.z()));
    }

    @Override
    public void expand(BlockVec... changes) throws RegionOperationException {
        center = center.add(diff(changes));
        radius(radius.add(changes(changes)));
    }

    @Override
    public void contract(BlockVec... changes) throws RegionOperationException {
        center = center.subtract(diff(changes));
        radius(Vec3.ONE.max(radius.subtract(changes(changes))));
    }

    @Override
    public void shift(BlockVec change) {
        center = center.add(change);
    }

    @Override
    public EllipsoidRegion copy() {
        return new EllipsoidRegion(center, radius);
    }

    @Override
    public String toString() {
        return center + " - " + radius;
    }
}
