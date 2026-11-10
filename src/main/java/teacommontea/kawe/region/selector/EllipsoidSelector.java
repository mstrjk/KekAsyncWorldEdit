package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.EllipsoidRegion;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class EllipsoidSelector implements Selector {

    protected BlockVec center;
    protected Vec3 radius = Vec3.ZERO;
    protected boolean selectedRadius;

    public EllipsoidSelector() {
    }

    public EllipsoidSelector(BlockVec center, Vec3 radius) {
        this.center = center;
        this.radius = radius;
        this.selectedRadius = true;
    }

    @Override
    public String typeName() {
        return "ellipsoid";
    }

    @Override
    public boolean selectPrimary(BlockVec pos) {
        if (pos.equals(center) && !selectedRadius) {
            return false;
        }
        center = pos;
        radius = Vec3.ZERO;
        selectedRadius = false;
        return true;
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (center == null) {
            return false;
        }
        Vec3 next = pos.toVec3().subtract(center.toVec3()).abs().max(radius);
        boolean changed = !selectedRadius || !next.equals(radius);
        radius = next;
        selectedRadius = true;
        return changed;
    }

    protected EllipsoidRegion build() {
        return new EllipsoidRegion(center, radius);
    }

    @Override
    public void explainPrimary(Audience to, BlockVec pos) {
        if (isDefined()) {
            Msg.print(to, "kawe.selection.ellipsoid.explain.primary-area", center.toVec3().toString(), volume());
        } else {
            Msg.print(to, "kawe.selection.ellipsoid.explain.primary", center.toVec3().toString());
        }
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        if (isDefined()) {
            Msg.print(to, "kawe.selection.ellipsoid.explain.secondary-area", radius.toString(), volume());
        } else {
            Msg.print(to, "kawe.selection.ellipsoid.explain.secondary", radius.toString());
        }
    }

    @Override
    public boolean isDefined() {
        return center != null && selectedRadius;
    }

    @Override
    public Region region() throws IncompleteRegionException {
        if (!isDefined()) {
            throw new IncompleteRegionException();
        }
        return build();
    }

    @Override
    public Optional<Region> partialRegion() {
        return center == null ? Optional.empty() : Optional.of(build());
    }

    @Override
    public BlockVec primary() throws IncompleteRegionException {
        if (center == null) {
            throw new IncompleteRegionException();
        }
        return center;
    }

    @Override
    public void learn(Region changed) {
        if (!(changed instanceof EllipsoidRegion ellipsoid)) {
            throw new IllegalArgumentException("Ellipsoid selector cannot learn " + changed.getClass().getSimpleName());
        }
        center = ellipsoid.centerBlock();
        radius = ellipsoid.radius();
        selectedRadius = true;
    }

    @Override
    public void clear() {
        center = null;
        radius = Vec3.ZERO;
        selectedRadius = false;
    }

    @Override
    public List<Component> info() {
        List<Component> lines = new ArrayList<>();
        if (center != null) {
            lines.add(Msg.tr("kawe.selection.ellipsoid.info.center", CuboidSelector.copyable(center.toVec3())));
        }
        if (selectedRadius) {
            lines.add(Msg.tr("kawe.selection.ellipsoid.info.radius", radius.toString()));
        }
        return lines;
    }

}
