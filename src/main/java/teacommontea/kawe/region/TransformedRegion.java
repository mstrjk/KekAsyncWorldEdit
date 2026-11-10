package teacommontea.kawe.region;

import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;

public final class TransformedRegion implements Region {

    private final Region source;
    private final Transform inverse;
    private final BlockVec origin;
    private final BlockVec min;
    private final BlockVec max;

    public TransformedRegion(Region source, Transform inverse, BlockVec origin, BlockVec min, BlockVec max) {
        this.source = source.copy();
        this.inverse = inverse;
        this.origin = origin;
        this.min = min;
        this.max = max;
    }

    @Override
    public BlockVec min() {
        return min;
    }

    @Override
    public BlockVec max() {
        return max;
    }

    @Override
    public long volume() {
        return source.volume();
    }

    public BlockVec sourceOf(int x, int y, int z) {
        Vec3 rel = inverse.apply(new Vec3(x - origin.x(), y - origin.y(), z - origin.z()));
        return new BlockVec((int) Math.round(rel.x()) + origin.x(), (int) Math.round(rel.y()) + origin.y(), (int) Math.round(rel.z()) + origin.z());
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (x < min.x() || x > max.x() || y < min.y() || y > max.y() || z < min.z() || z > max.z()) {
            return false;
        }
        return source.contains(sourceOf(x, y, z));
    }

    @Override
    public void expand(BlockVec... changes) throws RegionOperationException {
        throw new RegionOperationException("kawe.error.unsupported");
    }

    @Override
    public void contract(BlockVec... changes) throws RegionOperationException {
        throw new RegionOperationException("kawe.error.unsupported");
    }

    @Override
    public void shift(BlockVec change) throws RegionOperationException {
        throw new RegionOperationException("kawe.error.unsupported");
    }

    @Override
    public Region copy() {
        return this;
    }
}
