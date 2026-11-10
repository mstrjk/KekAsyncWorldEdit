package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.text.Msg;

public final class SphereSelector extends EllipsoidSelector {

    public SphereSelector() {
    }

    public SphereSelector(BlockVec center, double radius) {
        super(center, new Vec3(radius, radius, radius));
    }

    @Override
    public String typeName() {
        return "sphere";
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (center == null) {
            return false;
        }
        double length = Math.ceil(pos.toVec3().distance(center.toVec3()));
        Vec3 next = new Vec3(length, length, length);
        boolean changed = !selectedRadius || !next.equals(radius);
        radius = next;
        selectedRadius = true;
        return changed;
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        if (isDefined()) {
            Msg.print(to, "kawe.selection.sphere.explain.secondary-defined", radius.x(), volume());
        } else {
            Msg.print(to, "kawe.selection.sphere.explain.secondary", radius.x());
        }
    }
}
