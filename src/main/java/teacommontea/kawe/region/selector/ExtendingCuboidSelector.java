package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

public final class ExtendingCuboidSelector extends CuboidSelector {

    public ExtendingCuboidSelector() {
    }

    public ExtendingCuboidSelector(BlockVec pos1, BlockVec pos2) {
        super(pos1.min(pos2), pos1.max(pos2));
    }

    @Override
    public boolean selectPrimary(BlockVec pos) {
        if (pos.equals(position1) && pos.equals(position2)) {
            return false;
        }
        position1 = pos;
        position2 = pos;
        return true;
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (!isDefined()) {
            return selectPrimary(pos);
        }
        if (pos.containedWithin(position1, position2)) {
            return false;
        }
        position1 = pos.min(position1);
        position2 = pos.max(position2);
        return true;
    }

    @Override
    public void learn(Region changed) {
        if (!(changed instanceof CuboidRegion cuboid)) {
            throw new IllegalArgumentException("Cuboid selector cannot learn " + changed.getClass().getSimpleName());
        }
        position1 = cuboid.min();
        position2 = cuboid.max();
    }

    @Override
    public void explainPrimary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.extend.explain.primary", pos.toString(), volume());
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selection.extend.explain.secondary", pos.toString(), volume());
    }
}
