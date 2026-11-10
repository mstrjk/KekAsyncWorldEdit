package teacommontea.kawe.region.selector;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CuboidSelector implements Selector {

    protected BlockVec position1;
    protected BlockVec position2;

    public CuboidSelector() {
    }

    public CuboidSelector(BlockVec pos1, BlockVec pos2) {
        this.position1 = pos1;
        this.position2 = pos2;
    }

    @Override
    public String typeName() {
        return "cuboid";
    }

    @Override
    public boolean selectPrimary(BlockVec pos) {
        if (pos.equals(position1)) {
            return false;
        }
        position1 = pos;
        return true;
    }

    @Override
    public boolean selectSecondary(BlockVec pos) {
        if (pos.equals(position2)) {
            return false;
        }
        position2 = pos;
        return true;
    }

    @Override
    public void explainPrimary(Audience to, BlockVec pos) {
        if (isDefined()) {
            Msg.print(to, "kawe.selection.cuboid.explain.primary-area", pos.toString(), volume());
        } else {
            Msg.print(to, "kawe.selection.cuboid.explain.primary", pos.toString());
        }
    }

    @Override
    public void explainSecondary(Audience to, BlockVec pos) {
        if (isDefined()) {
            Msg.print(to, "kawe.selection.cuboid.explain.secondary-area", pos.toString(), volume());
        } else {
            Msg.print(to, "kawe.selection.cuboid.explain.secondary", pos.toString());
        }
    }

    @Override
    public boolean isDefined() {
        return position1 != null && position2 != null;
    }

    @Override
    public Region region() throws IncompleteRegionException {
        if (!isDefined()) {
            throw new IncompleteRegionException();
        }
        return new CuboidRegion(position1, position2);
    }

    @Override
    public Optional<Region> partialRegion() {
        if (isDefined()) {
            return Optional.of(new CuboidRegion(position1, position2));
        }
        BlockVec only = position1 != null ? position1 : position2;
        return only == null ? Optional.empty() : Optional.of(new CuboidRegion(only, only));
    }

    @Override
    public BlockVec primary() throws IncompleteRegionException {
        if (position1 == null) {
            throw new IncompleteRegionException();
        }
        return position1;
    }

    @Override
    public void learn(Region changed) {
        if (!(changed instanceof CuboidRegion cuboid)) {
            throw new IllegalArgumentException("Cuboid selector cannot learn " + changed.getClass().getSimpleName());
        }
        position1 = cuboid.pos1();
        position2 = cuboid.pos2();
    }

    @Override
    public void clear() {
        position1 = null;
        position2 = null;
    }

    @Override
    public List<Component> info() {
        List<Component> lines = new ArrayList<>();
        if (position1 != null) {
            lines.add(Msg.tr("kawe.selection.cuboid.info.pos1", copyable(position1)));
        }
        if (position2 != null) {
            lines.add(Msg.tr("kawe.selection.cuboid.info.pos2", copyable(position2)));
        }
        return lines;
    }

    static Component copyable(Object value) {
        String text = value.toString();
        return Component.text(text)
                .clickEvent(ClickEvent.copyToClipboard(text))
                .hoverEvent(HoverEvent.showText(Component.text("Click to copy")));
    }

}
