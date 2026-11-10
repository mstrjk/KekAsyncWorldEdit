package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChangeSet;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.engine.SideEffect;
import teacommontea.kawe.history.DiskHistory;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.session.Sessions;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class HistoryCommands {

    private static final ArgType<String> PLAYER = new ArgType<>() {
        @Override
        public String parse(String raw, CmdContext context) {
            return raw;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), partial);
        }
    };

    private static final String[] COMPASS = {"NORTH", "NORTH_NORTHEAST", "NORTHEAST", "EAST_NORTHEAST", "EAST", "EAST_SOUTHEAST", "SOUTHEAST", "SOUTH_SOUTHEAST",
            "SOUTH", "SOUTH_SOUTHWEST", "SOUTHWEST", "WEST_SOUTHWEST", "WEST", "WEST_NORTHWEST", "NORTHWEST", "NORTH_NORTHWEST"};

    private static final Pattern TIME_PART = Pattern.compile(
            "(\\d+)\\s*(years?|y|weeks?|wks?|w|days?|d|hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)");

    static final ArgType<Long> TIME = (raw, context) -> {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.matches("\\d+")) {
            return Long.parseLong(text) * 1000L;
        }
        Matcher matcher = TIME_PART.matcher(text);
        long total = 0;
        int end = 0;
        while (matcher.find()) {
            if (!text.substring(end, matcher.start()).isBlank()) {
                break;
            }
            long amount = Long.parseLong(matcher.group(1));
            total += amount * switch (matcher.group(2).charAt(0)) {
                case 'y' -> 31536000000L;
                case 'w' -> 604800000L;
                case 'd' -> 86400000L;
                case 'h' -> 3600000L;
                case 'm' -> 60000L;
                default -> 1000L;
            };
            end = matcher.end();
        }
        if (end == 0 || !text.substring(end).isBlank()) {
            throw new InputParseException(Component.text("Invalid time: " + raw + " (e.g. 20s, 5m, 1h30m, 2d)"));
        }
        return total;
    };

    static final ArgType<UUID> USER = new ArgType<>() {
        @Override
        public UUID parse(String raw, CmdContext context) throws InputParseException {
            String text = raw.trim();
            if (text.equals("*")) {
                return DiskHistory.EVERYONE;
            }
            if (text.equalsIgnoreCase("me")) {
                return context.session().owner();
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(text);
                if (player == null) {
                    throw InputParseException.of("kawe.error.player.not.found", text);
                }
                return player.getUniqueId();
            }
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Stream.concat(Stream.of("*", "me"), Bukkit.getOnlinePlayers().stream().map(Player::getName)), partial);
        }
    };

    private HistoryCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("undo").aliases("/undo").confirm(Confirm.LIMIT, "times").perm("kawe.history.undo").desc("Undoes the last action (from history)")
                .opt("times", Types.INT, "1").opt("player", PLAYER, "").flag('e', "Leave entities as they are").run(c -> step(c, true)));
        Commands.add(Cmd.of("redo").aliases("/redo").confirm(Confirm.LIMIT, "times").perm("kawe.history.redo").desc("Redoes the last action (from history)")
                .opt("times", Types.INT, "1").opt("player", PLAYER, "").flag('e', "Leave entities as they are").run(c -> step(c, false)));
        Commands.add(Cmd.of("clearhistory").aliases("/clearhistory").perm("kawe.history.clear").desc("Clear your history")
                .run(HistoryCommands::clear));
        Commands.add(Cmd.of("/history").aliases("/frb").desc("Manage your history")
                .sub(Cmd.of("restore").aliases("rerun").perm("kawe.history.restore").desc("Rerun edits - The time uses s, m, h, d, y.").player().confirm(Confirm.ALWAYS)
                        .flag('u', "String user", USER).flag('r', "radius", Types.INT).flag('t', "Time e.g. 20s", TIME).run(c -> rollback(c, true)))
                .sub(Cmd.of("rollback").perm("kawe.history.rollback").desc("Undo a specific edit. - The time uses s, m, h, d, y.").player().confirm(Confirm.ALWAYS)
                        .flag('u', "String user", USER).flag('r', "radius", Types.INT).flag('t', "Time e.g. 20s", TIME).flag('f', "Restore instead of rollback")
                        .run(c -> rollback(c, c.flag('f'))))
                .sub(Cmd.of("info").aliases("summary", "summarize").perm("kawe.history.info").desc("Summarize an edit").player()
                        .arg("player", USER).arg("index", Types.INT).run(HistoryCommands::info))
                .sub(Cmd.of("find").aliases("inspect", "search", "near").perm("kawe.history.find").desc("Find nearby edits").player()
                        .flag('u', "String user", USER).flag('r', "radius", Types.INT).flag('t', "Time e.g. 20s", TIME).flag('p', "Page to view.", Types.INT)
                        .run(HistoryCommands::find))
                .sub(Cmd.of("distr").aliases("distribution").perm("kawe.history.distr").desc("View block distribution for an edit")
                        .arg("player", USER).arg("index", Types.INT).flag('p', "Page to view.", Types.INT).run(HistoryCommands::distr))
                .sub(Cmd.of("list").perm("kawe.history.list").desc("List your history").player()
                        .opt("player", USER, "").flag('p', "Page to view.", Types.INT).run(HistoryCommands::list))
                .sub(Cmd.of("clear").perm("kawe.history.clear").desc("Clear your history").run(HistoryCommands::clear)));
    }

    private static void clear(CmdContext c) {
        c.session().clearHistory();
        c.print("kawe.clearhistory.cleared");
    }

    private static void requireDisk() throws CommandError {
        if (!DiskHistory.enabled()) {
            throw new CommandError("kawe.error.setting.disable", "history.use-disk");
        }
    }

    private static UUID owner(CmdContext c, UUID requested) throws CommandError {
        UUID self = c.session().owner();
        UUID target = requested == null ? self : requested;
        if (!target.equals(self) && !c.actor().has("kawe.history.undo.other")) {
            throw new CommandError("kawe.error.no-perm", "kawe.history.undo.other");
        }
        return DiskHistory.EVERYONE.equals(target) ? null : target;
    }

    private static int[] box(KWorld world, BlockVec at, Integer radius) {
        if (radius == null) {
            return null;
        }
        return new int[]{at.x() - radius, Math.max(world.minY(), at.y() - radius), at.z() - radius,
                at.x() + radius, Math.min(world.maxY(), at.y() + radius), at.z() + radius};
    }

    private static void rollback(CmdContext c, boolean restore) throws Exception {
        requireDisk();
        Integer radius = c.flagValue('r', Types.INT);
        Long time = c.flagValue('t', TIME);
        if (radius == null || radius <= 0) {
            throw new CommandError("kawe.error.radius-too-small");
        }
        if (time == null || time <= 0) {
            throw new CommandError("kawe.error.time-too-less");
        }
        UUID owner = owner(c, c.flagValue('u', USER));
        KWorld world = c.world();
        List<DiskHistory.Entry> edits = new ArrayList<>(DiskHistory.edits(world.bukkit().getName(), owner, System.currentTimeMillis() - time,
                box(world, c.actor().block(), radius), restore ? DiskHistory.State.ROLLED_BACK : DiskHistory.State.ACTIVE));
        if (restore) {
            Collections.reverse(edits);
        }
        int count = 0;
        int entityFailures = 0;
        for (DiskHistory.Entry entry : edits) {
            ChangeSet changes = DiskHistory.load(entry, world);
            Edit.Result result = CmdContext.join(Edit.commit(world, changes.toChanges(!restore), null, c.session().sideEffects()));
            c.worked();
            if (result.failed()) {
                c.print("kawe.tool.tool.inspect.info.footer", count);
                throw new PartialCommitException(result.failures().size(), result.failures().getFirst());
            }
            if (changes.hasEntities()) {
                entityFailures += changes.replayEntities(!restore, c.session().sideEffects().contains(SideEffect.EVENTS));
                DiskHistory.store(entry, changes);
            }
            DiskHistory.mark(entry, !restore, !restore);
            count++;
            c.print(restore ? "kawe.history.restored" : "kawe.rollback.rollback.element", entry.world() + "/" + entry.owner() + "-" + entry.index());
        }
        c.print("kawe.tool.tool.inspect.info.footer", count);
        if (entityFailures > 0) {
            c.error("kawe.history.entity-failures", entityFailures);
        }
    }

    private static void find(CmdContext c) throws Exception {
        requireDisk();
        UUID requested = c.flagValue('u', USER);
        Integer radius = c.flagValue('r', Types.INT);
        Long time = c.flagValue('t', TIME);
        if (requested == null && radius == null && time == null) {
            throw new CommandError("kawe.error.invalid-user");
        }
        if (radius != null && radius <= 0) {
            throw new CommandError("kawe.error.radius-too-small");
        }
        if (time != null && time <= 0) {
            throw new CommandError("kawe.error.time-too-less");
        }
        UUID owner = owner(c, requested);
        KWorld world = c.world();
        BlockVec origin = c.actor().block();
        List<Component> lines = new ArrayList<>();
        for (DiskHistory.Entry entry : DiskHistory.edits(world.bukkit().getName(), owner, time == null ? Long.MIN_VALUE : System.currentTimeMillis() - time,
                box(world, origin, radius), null)) {
            lines.add(element(entry.owner(), entry.index(), entry.time(), entry.command(), entry.bounds(), entry.size(), origin));
        }
        page(c, "Edits:", lines, c.flagValue('p', Types.INT), 8);
    }

    private static void list(CmdContext c) throws Exception {
        UUID requested = c.get("player", USER);
        Session target = c.session();
        if (requested != null && !requested.equals(target.owner())) {
            if (!c.actor().has("kawe.history.undo.other")) {
                throw new CommandError("kawe.error.no-perm", "kawe.history.undo.other");
            }
            target = Sessions.existing(requested);
            if (target == null) {
                c.print("kawe.session.cant-find-session", c.raw("player"));
                return;
            }
        }
        BlockVec origin = c.actor().block();
        List<Component> lines = new ArrayList<>();
        List<ChangeSet> history = target.historyView();
        for (int i = history.size() - 1; i >= 0; i--) {
            ChangeSet changes = history.get(i);
            int[] bounds = changes.bounds();
            if (bounds != null) {
                lines.add(element(target.owner(), changes.diskIndex(), changes.created(), changes.command(), bounds, changes.size(), origin));
            }
        }
        page(c, "Edits:", lines, c.flagValue('p', Types.INT), 8);
    }

    private static void info(CmdContext c) throws Exception {
        requireDisk();
        UUID owner = c.get("player", USER);
        int index = c.get("index", Types.INT);
        KWorld world = c.world();
        DiskHistory.Entry entry = DiskHistory.get(world.bukkit().getName(), owner, index);
        if (entry == null) {
            c.print("kawe.schematic.schematic.none");
            return;
        }
        ChangeSet changes = DiskHistory.load(entry, world);
        int[] b = entry.bounds();
        c.print(element(entry.owner(), -1, entry.time(), entry.command(), b, entry.size(), c.actor().block()));
        c.print(Msg.tr("kawe.history.find.element.more", entry.size(), new BlockVec(b[0], b[1], b[2]), new BlockVec(b[3], b[4], b[5]),
                String.join(", ", changes.extras()), bytes(entry.diskSize())));
        c.print(Component.text("/history distr").clickEvent(ClickEvent.suggestCommand("//history distr " + entry.owner() + " " + entry.index())));
    }

    private static void distr(CmdContext c) throws Exception {
        requireDisk();
        UUID owner = c.get("player", USER);
        int index = c.get("index", Types.INT);
        KWorld world = c.world();
        DiskHistory.Entry entry = DiskHistory.get(world.bukkit().getName(), owner, index);
        if (entry == null) {
            c.print("kawe.history.distr.summary_null");
            return;
        }
        List<Session.Count> distribution = new ArrayList<>();
        DiskHistory.load(entry, world).distribution().forEach((state, count) -> distribution.add(new Session.Count(state, count)));
        distribution.sort((a, b) -> Long.compare(b.amount(), a.amount()));
        if (distribution.isEmpty()) {
            c.print("kawe.distr.no-blocks");
            return;
        }
        c.print("kawe.distr.total", distribution.stream().mapToLong(Session.Count::amount).sum());
        page(c, "Block Distribution", SelectionCommands.distributionLines(distribution, true), c.flagValue('p', Types.INT), 7);
    }

    private static void page(CmdContext c, String title, List<Component> lines, Integer page, int perPage) {
        String command = c.commandLine().replaceAll("\\s-p\\s+-?\\d+", "").trim();
        try {
            c.print(new PaginationBox(title, command + " -p %page%", lines).perPage(perPage).forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    private static Component element(UUID owner, int index, long time, String command, int[] bounds, long size, BlockVec origin) {
        double dx = Math.max(Math.max(bounds[0] - origin.x(), origin.x() - bounds[3]), 0);
        double dz = Math.max(Math.max(bounds[2] - origin.z(), origin.z() - bounds[5]), 0);
        int distance = (int) Math.sqrt(dx * dx + dz * dz);
        double cx = (bounds[0] + bounds[3]) / 2.0 - origin.x();
        double cz = (bounds[2] + bounds[5]) / 2.0 - origin.z();
        int compass = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(cx, -cz)) / 22.5), 16);
        Component out = Msg.tr("kawe.history.find.element", DiskHistory.name(owner), age(System.currentTimeMillis() - time), distance, COMPASS[compass], command)
                .hoverEvent(HoverEvent.showText(Msg.tr("kawe.history.find.hover", size)));
        if (index >= 0) {
            out = out.clickEvent(ClickEvent.runCommand("//history summary " + owner + " " + index));
        }
        return out;
    }

    public static String age(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long[] sizes = {31536000L, 604800L, 86400L, 3600L, 60L, 1L};
        String[] units = {"y", "w", "d", "h", "m", "s"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sizes.length; i++) {
            long amount = seconds / sizes[i];
            if (amount > 0) {
                seconds -= amount * sizes[i];
                out.append(out.isEmpty() ? "" : " ").append(amount).append(units[i]);
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }

    private static String bytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        int unit = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format(Locale.ROOT, "%.1f %siB", size / (double) (1L << (unit * 10)), "KMGTPE".charAt(unit - 1));
    }

    private static void step(CmdContext c, boolean undo) throws Exception {
        int times = c.get("times", Types.INT);
        if (times < 1) {
            throw new CommandError(Component.text("Times must be >= 1"));
        }
        if (c.session().fast()) {
            c.print("kawe.history.command.undo.disabled");
            return;
        }
        String name = c.get("player", PLAYER);
        Session target = c.session();
        if (name != null) {
            String permission = undo ? "kawe.history.undo.other" : "kawe.history.redo.other";
            if (!c.actor().has(permission)) {
                throw new CommandError("kawe.error.no-perm", permission);
            }
            OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
            target = player == null ? null : Sessions.existing(player.getUniqueId());
            if (target == null) {
                c.print("kawe.session.cant-find-session", name);
                return;
            }
        }
        Session history = target;
        if (history == c.session()) {
            replay(c, history, undo, times);
        } else {
            CmdContext.join(history.queue(() -> Sched.async(() -> {
                try {
                    replay(c, history, undo, times);
                } catch (CommandError e) {
                    throw new CompletionException(e);
                }
            })));
        }
    }

    private static void replay(CmdContext c, Session history, boolean undo, int times) throws CommandError {
        int done = 0;
        long blocks = 0;
        int entityFailures = 0;
        for (int i = 0; i < times; i++) {
            ChangeSet changes = undo ? history.peekUndo() : history.peekRedo();
            if (changes == null) {
                break;
            }
            if (Bukkit.getWorld(changes.world().bukkit().getUID()) == null) {
                report(c, undo, done, blocks);
                throw new CommandError("kawe.error.world-unloaded");
            }
            Edit.Result result = CmdContext.join(Edit.commit(changes.world(), changes.toChanges(undo), null, history.sideEffects()));
            c.worked();
            blocks += result.changed();
            if (result.failed()) {
                report(c, undo, done, blocks);
                throw new PartialCommitException(result.failures().size(), result.failures().getFirst());
            }
            if (c.flag('e')) {
                changes.dropEntities();
            } else {
                entityFailures += changes.replayEntities(undo, history.sideEffects().contains(SideEffect.EVENTS));
            }
            history.advance(changes, undo);
            DiskHistory.mark(changes, undo);
            done++;
        }
        report(c, undo, done, blocks);
        if (entityFailures > 0) {
            c.error("kawe.history.entity-failures", entityFailures);
        }
    }

    private static void report(CmdContext c, boolean undo, int done, long blocks) {
        if (done > 0) {
            c.print(undo ? "kawe.undo.undone" : "kawe.redo.redone", done, blocks);
        } else {
            c.print(undo ? "kawe.undo.none" : "kawe.redo.none");
        }
    }
}
