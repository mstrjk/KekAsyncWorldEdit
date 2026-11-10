package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import teacommontea.kawe.math.BlockVec2;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.CylinderRegion;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CylinderSelector implements Selector {

    private BlockVec center;
    private double radiusX;
    private double radiusZ;
    private int minY;
    private int maxY;
    private boolean selectedRadius;

    public CylinderSelector() {
    }

    public CylinderSelector(BlockVec center, double radiusX, double radiusZ, int minY, int maxY) {
        this.center = center;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.minY = minY;
        this.maxY = maxY;
        this.selectedRadius = true;
    }

    @Override
    public String typeName() {
        return "Cylinder";
    }

    @Override
    public boolean selectPrimary(BlockVec pos) {
        if (pos.equals(center) && !selectedRadius) {
            return false;
        }
        center = pos;
        radiusX = 0;
        radiusZ = 0;
        minY = pos.y();
        maxY = pos.y();
        selectedRadius = false;
        return true;
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (center == null) {
            return true;
        }
        double nextX = Math.max(radiusX, Math.abs((long) pos.x() - center.x()));
        double nextZ = Math.max(radiusZ, Math.abs((long) pos.z() - center.z()));
        int nextMin = Math.min(minY, pos.y());
        int nextMax = Math.max(maxY, pos.y());
        boolean changed = !selectedRadius || nextX != radiusX || nextZ != radiusZ || nextMin != minY || nextMax != maxY;
        radiusX = nextX;
        radiusZ = nextZ;
        minY = nextMin;
        maxY = nextMax;
        selectedRadius = true;
        return changed;
    }

    private CylinderRegion build() {
        return new CylinderRegion(center, radiusX, radiusZ, minY, maxY);
    }

    @Override
    public void explainPrimary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.cylinder.explain.primary", pos.toString());
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        if (center == null) {
            Msg.print(to, "kawe.selection.cylinder.explain.secondary-missing");
            return;
        }
        Msg.print(to, "kawe.selection.cylinder.explain.secondary", Selector.number(radiusX), Selector.number(radiusZ), volume());
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
        if (!(changed instanceof CylinderRegion cylinder)) {
            throw new IllegalArgumentException("Cylinder selector cannot learn " + changed.getClass().getSimpleName());
        }
        BlockVec2 column = cylinder.centerColumn();
        int y = center == null ? cylinder.minY() : Math.clamp(center.y(), cylinder.minY(), cylinder.maxY());
        center = new BlockVec(column.x(), y, column.z());
        radiusX = cylinder.radiusX();
        radiusZ = cylinder.radiusZ();
        minY = cylinder.minY();
        maxY = cylinder.maxY();
        selectedRadius = true;
    }

    @Override
    public void clear() {
        center = null;
        radiusX = 0;
        radiusZ = 0;
        minY = 0;
        maxY = 0;
        selectedRadius = false;
    }

    @Override
    public List<Component> info() {
        List<Component> lines = new ArrayList<>();
        if (center != null) {
            lines.add(Msg.tr("kawe.selection.cylinder.info.center", CuboidSelector.copyable(center)));
        }
        if (selectedRadius) {
            lines.add(Msg.tr("kawe.selection.cylinder.info.radius", "(" + Selector.number(radiusX) + ", " + Selector.number(radiusZ) + ")"));
        }
        return lines;
    }

}
