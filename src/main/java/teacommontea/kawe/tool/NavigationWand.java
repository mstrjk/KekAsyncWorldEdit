package teacommontea.kawe.tool;

import teacommontea.kawe.Settings;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.session.Navigation;

public final class NavigationWand implements Tool {

    public static final NavigationWand INSTANCE = new NavigationWand();

    private NavigationWand() {
    }

    @Override
    public String permission() {
        return null;
    }

    @Override
    public boolean leftClick(ToolClick click) {
        if (!click.player().hasPermission("kawe.navigation.jumpto.tool")) {
            return false;
        }
        click.run("jumpto", c -> {
            c.requireActorWorld();
            Edit reader = c.reader();
            Actor actor = c.actor();
            BlockVec hit = Navigation.solidTrace(reader, actor.eye(), actor.yaw(), actor.pitch(), Settings.get().navigationWandDistance());
            if (hit == null) {
                c.print("kawe.jumpto.none");
                return;
            }
            Navigation.Target target = Navigation.free(reader, hit);
            if (target != null) {
                Navigation.teleport(click.player(), c.world().bukkit(), target, actor.yaw(), actor.pitch());
            }
        });
        return true;
    }

    @Override
    public boolean rightClick(ToolClick click) {
        if (!click.player().hasPermission("kawe.navigation.thru.tool")) {
            return false;
        }
        click.run("thru", c -> {
            c.requireActorWorld();
            Actor actor = c.actor();
            Navigation.Target target = Navigation.thru(c.reader(), actor.eye(), actor.yaw(), actor.pitch(), 40);
            if (target == null) {
                c.print("kawe.thru.obstructed");
                return;
            }
            Navigation.teleport(click.player(), c.world().bukkit(), target, actor.yaw(), actor.pitch());
        });
        return true;
    }
}
