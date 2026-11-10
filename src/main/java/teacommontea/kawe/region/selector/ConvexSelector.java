package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.ConvexPolyhedralRegion;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ConvexSelector implements Selector {

    private final List<BlockVec> points = new ArrayList<>();
    private final int vertexLimit;

    public ConvexSelector(int vertexLimit) {
        this.vertexLimit = vertexLimit;
    }

    public ConvexSelector(List<BlockVec> points, int vertexLimit) {
        this(vertexLimit);
        this.points.addAll(points);
    }

    @Override
    public String typeName() {
        return "convex";
    }

    @Override
    public boolean selectPrimary(BlockVec pos) {
        points.clear();
        points.add(pos);
        return true;
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (vertexLimit >= 0 && points.size() >= vertexLimit) {
            return false;
        }
        ConvexPolyhedralRegion before = new ConvexPolyhedralRegion(points);
        if (points.contains(pos) || !before.addVertex(pos)) {
            return false;
        }
        points.add(pos);
        return true;
    }

    @Override
    public void explainPrimary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.convex.explain.primary", pos.toString());
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.convex.explain.secondary", pos.toString());
    }

    @Override
    public boolean isDefined() {
        return new ConvexPolyhedralRegion(points).isDefined();
    }

    @Override
    public Region region() throws IncompleteRegionException {
        ConvexPolyhedralRegion region = new ConvexPolyhedralRegion(points);
        if (!region.isDefined()) {
            throw new IncompleteRegionException();
        }
        return region;
    }

    @Override
    public Optional<Region> partialRegion() {
        ConvexPolyhedralRegion region = new ConvexPolyhedralRegion(points);
        return region.isDefined() ? Optional.of(region) : Optional.empty();
    }

    @Override
    public BlockVec primary() throws IncompleteRegionException {
        if (points.isEmpty()) {
            throw new IncompleteRegionException();
        }
        return points.getFirst();
    }

    @Override
    public void learn(Region changed) {
        if (!(changed instanceof ConvexPolyhedralRegion convex)) {
            throw new IllegalArgumentException("Convex selector cannot learn " + changed.getClass().getSimpleName());
        }
        points.clear();
        points.addAll(convex.vertices());
    }

    @Override
    public void clear() {
        points.clear();
    }

    @Override
    public List<Component> info() {
        ConvexPolyhedralRegion region = new ConvexPolyhedralRegion(points);
        return List.of(Msg.tr("kawe.selection.convex.info.vertices", region.vertices().size()),
                Msg.tr("kawe.selection.convex.info.triangles", region.triangles().size()));
    }

}
