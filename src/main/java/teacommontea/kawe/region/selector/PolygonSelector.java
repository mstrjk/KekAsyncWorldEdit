package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import teacommontea.kawe.math.BlockVec2;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Polygonal2DRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PolygonSelector implements Selector {

    private final int worldMinY;
    private final int worldMaxY;
    private final int vertexLimit;
    private final List<BlockVec2> points = new ArrayList<>();
    private BlockVec pos1;
    private int minY;
    private int maxY;

    public PolygonSelector(int worldMinY, int worldMaxY, int vertexLimit) {
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
        this.vertexLimit = vertexLimit;
    }

    public PolygonSelector(List<BlockVec2> points, int minY, int maxY, int worldMinY, int worldMaxY, int vertexLimit) {
        this(worldMinY, worldMaxY, vertexLimit);
        this.points.addAll(points);
        this.minY = minY;
        this.maxY = maxY;
        this.pos1 = points.isEmpty() ? null : points.getFirst().toBlock(minY);
    }

    @Override
    public String typeName() {
        return "2Dx1D polygon";
    }

    @Override
    public boolean selectPrimary(BlockVec pos) {
        if (pos.equals(pos1)) {
            return false;
        }
        pos1 = pos;
        points.clear();
        points.add(new BlockVec2(pos.x(), pos.z()));
        minY = pos.y();
        maxY = pos.y();
        return true;
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (!points.isEmpty()) {
            BlockVec2 last = points.getLast();
            if (last.x() == pos.x() && last.z() == pos.z()) {
                return false;
            }
            if (vertexLimit >= 0 && points.size() > vertexLimit) {
                return false;
            }
        } else {
            pos1 = pos;
            minY = pos.y();
            maxY = pos.y();
        }
        points.add(new BlockVec2(pos.x(), pos.z()));
        minY = Math.min(minY, pos.y());
        maxY = Math.max(maxY, pos.y());
        return true;
    }

    private Polygonal2DRegion build() {
        return new Polygonal2DRegion(points, minY, maxY, worldMinY, worldMaxY);
    }

    @Override
    public void explainPrimary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.polygon2d.explain.primary", pos.toString());
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.polygon2d.explain.secondary", points.size(), pos.toString());
    }

    @Override
    public boolean isDefined() {
        return points.size() > 2;
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
        return points.isEmpty() ? Optional.empty() : Optional.of(build());
    }

    @Override
    public BlockVec primary() throws IncompleteRegionException {
        if (pos1 == null) {
            throw new IncompleteRegionException();
        }
        return pos1;
    }

    @Override
    public void learn(Region changed) {
        if (!(changed instanceof Polygonal2DRegion polygon)) {
            throw new IllegalArgumentException("Polygon selector cannot learn " + changed.getClass().getSimpleName());
        }
        points.clear();
        points.addAll(polygon.points());
        minY = polygon.minY();
        maxY = polygon.maxY();
        pos1 = points.isEmpty() ? null : points.getFirst().toBlock(minY);
    }

    @Override
    public void clear() {
        points.clear();
        pos1 = null;
        minY = 0;
        maxY = 0;
    }

    @Override
    public List<Component> info() {
        return List.of(Msg.tr("kawe.selection.polygon2d.info", points.size()));
    }

}
