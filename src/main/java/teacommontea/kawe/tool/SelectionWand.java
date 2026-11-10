package teacommontea.kawe.tool;

import org.bukkit.entity.Player;
import teacommontea.kawe.engine.Preload;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.selector.Selector;

public final class SelectionWand implements Tool {

    public static final SelectionWand INSTANCE = new SelectionWand();

    private SelectionWand() {
    }

    @Override
    public String permission() {
        return "kawe.selection.pos";
    }

    @Override
    public boolean rightClick(ToolClick click) {
        return select(click, false);
    }

    @Override
    public boolean leftClick(ToolClick click) {
        return select(click, true);
    }

    private static boolean select(ToolClick click, boolean primary) {
        BlockVec pos = click.block();
        if (pos == null) {
            return false;
        }
        Player player = click.player();
        Selector selector = click.session().selector(player.getWorld());
        if (selector.needsWorld()) {
            click.run("select", c -> {
                if (primary ? selector.selectPrimary(pos) : selector.selectSecondary(pos)) {
                    selector.resolve(c.reader());
                    Preload.selected(player.getUniqueId());
                    if (primary) {
                        selector.explainPrimary(player, pos);
                    } else {
                        selector.explainSecondary(player, pos);
                    }
                }
            });
            return true;
        }
        boolean changed = primary ? selector.selectPrimary(pos) : selector.selectSecondary(pos);
        if (changed) {
            Preload.selected(player.getUniqueId());
            if (primary) {
                selector.explainPrimary(player, pos);
            } else {
                selector.explainSecondary(player, pos);
            }
        }
        return true;
    }
}
