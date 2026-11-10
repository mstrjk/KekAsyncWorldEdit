package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import teacommontea.kawe.Settings;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.session.Navigation;

public final class NavigationCommands {

    private NavigationCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("unstuck").aliases("!", "/unstuck").perm("kawe.navigation.unstuck").desc("Escape from being stuck inside a block")
                .player().run(NavigationCommands::unstuck));
        Commands.add(Cmd.of("ascend").aliases("asc", "/asc", "/ascend").perm("kawe.navigation.ascend").desc("Go up a floor")
                .player().opt("levels", Types.INT, "1").run(c -> levels(c, true)));
        Commands.add(Cmd.of("descend").aliases("desc", "/desc", "/descend").perm("kawe.navigation.descend").desc("Go down a floor")
                .player().opt("levels", Types.INT, "1").run(c -> levels(c, false)));
        Commands.add(Cmd.of("ceil").aliases("/ceil", "/ceiling").perm("kawe.navigation.ceiling").desc("Go to the ceiling")
                .player().opt("clearance", Types.INT, "0").flag('f', "Force using flight to keep you still")
                .flag('g', "Force using glass to keep you still").run(NavigationCommands::ceiling));
        Commands.add(Cmd.of("thru").aliases("/thru").perm("kawe.navigation.thru.command").desc("Pass through walls")
                .player().run(NavigationCommands::thru));
        Commands.add(Cmd.of("jumpto").aliases("j", "/jumpto", "/j").perm("kawe.navigation.jumpto.command").desc("Teleport to a location")
                .player().opt("pos", Types.VECTOR, "").flag('f', "force teleport").run(NavigationCommands::jumpTo));
        Commands.add(Cmd.of("up").aliases("/up").perm("kawe.navigation.up").desc("Go upwards some distance")
                .player().arg("distance", Types.INT).flag('f', "Force using flight to keep you still")
                .flag('g', "Force using glass to keep you still").run(NavigationCommands::up));
    }

    private static Edit reader(CmdContext c) throws CommandError {
        c.requireActorWorld();
        return c.reader();
    }

    private static boolean alwaysGlass(CmdContext c) throws CommandError {
        if (c.flag('f') && c.flag('g')) {
            throw new CommandError(Component.text("Use only one of -f and -g"));
        }
        return c.flag('g') || (Settings.get().navigationGlass() && !c.flag('f'));
    }

    private static void unstuck(CmdContext c) throws Exception {
        Navigation.Target target = Navigation.free(reader(c), c.actor().block());
        if (target == null) {
            c.print("kawe.unstuck.free");
            return;
        }
        c.teleport(target);
        c.print("kawe.unstuck.moved");
    }

    private static void levels(CmdContext c, boolean up) throws Exception {
        int levels = c.get("levels", Types.INT);
        if (levels < 1) {
            throw new CommandError(Component.text("Levels must be >= 1"));
        }
        Edit world = reader(c);
        BlockVec at = c.actor().block();
        Navigation.Target last = null;
        int moved = 0;
        while (moved < levels) {
            Navigation.Target next = up ? Navigation.ascend(world, at) : Navigation.descend(world, at);
            if (next == null) {
                break;
            }
            last = next;
            at = new BlockVec((int) Math.floor(next.x()), (int) Math.floor(next.y()), (int) Math.floor(next.z()));
            moved++;
        }
        String prefix = up ? "kawe.ascend." : "kawe.descend.";
        if (last == null) {
            c.print(prefix + "obstructed");
            return;
        }
        c.teleport(last);
        c.print(prefix + "moved", moved);
    }

    private static void ceiling(CmdContext c) throws Exception {
        int clearance = c.get("clearance", Types.INT);
        if (clearance < 0) {
            throw new CommandError(Component.text("Clearance must be >= 0"));
        }
        BlockVec at = c.actor().block();
        Integer y = Navigation.ceiling(reader(c), at, clearance);
        if (y == null) {
            c.print("kawe.ceil.obstructed");
            return;
        }
        c.floatAt(at.x(), y, at.z(), alwaysGlass(c));
        c.print("kawe.ceil.moved");
    }

    private static void thru(CmdContext c) throws Exception {
        Navigation.Target target = Navigation.thru(reader(c), c.actor().eye(), c.actor().yaw(), c.actor().pitch(), 6);
        if (target == null) {
            c.print("kawe.thru.obstructed");
            return;
        }
        c.teleport(target);
        c.print("kawe.thru.moved");
    }

    private static void jumpTo(CmdContext c) throws Exception {
        Edit world = reader(c);
        BlockVec pos = c.get("pos", Types.VECTOR);
        if (pos == null) {
            pos = Navigation.solidTrace(world, c.actor().eye(), c.actor().yaw(), c.actor().pitch(), 300);
        }
        if (pos == null) {
            c.print("kawe.jumpto.none");
            return;
        }
        Navigation.Target target = c.flag('f') ? new Navigation.Target(pos.x() + 0.5, pos.y(), pos.z() + 0.5) : Navigation.free(world, pos);
        if (target == null) {
            c.print("kawe.jumpto.none");
            return;
        }
        c.teleport(target);
        c.print("kawe.jumpto.moved");
    }

    private static void up(CmdContext c) throws Exception {
        int distance = c.get("distance", Types.INT);
        if (distance < 1) {
            throw new CommandError(Component.text("Distance must be >= 1"));
        }
        BlockVec at = c.actor().block();
        Integer y = Navigation.upwards(reader(c), at, distance);
        if (y == null) {
            c.print("kawe.up.obstructed");
            return;
        }
        c.floatAt(at.x(), y, at.z(), alwaysGlass(c));
        c.print("kawe.up.moved");
    }
}
