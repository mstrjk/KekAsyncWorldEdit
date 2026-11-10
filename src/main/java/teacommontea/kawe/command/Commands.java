package teacommontea.kawe.command;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import teacommontea.kawe.Debug;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Tuning;
import teacommontea.kawe.engine.ChangeSet;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.EditCancelledException;
import teacommontea.kawe.engine.MaxChangedBlocksException;
import teacommontea.kawe.engine.Preload;
import teacommontea.kawe.engine.RegionQueue;
import teacommontea.kawe.history.DiskHistory;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.RegionOperationException;
import teacommontea.kawe.region.selector.Selector;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.session.Sessions;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

public final class Commands {

    private static final UUID CONSOLE = new UUID(0, 0);
    private static final Map<String, Cmd> REGISTRY = new LinkedHashMap<>();
    private static final Set<String> LABELS = new HashSet<>();
    private static volatile boolean frozen;

    private Commands() {
    }

    public static synchronized void add(Cmd command) {
        if (frozen) {
            throw new IllegalStateException("Commands are registered at startup only: " + command.name);
        }
        List<String> labels = new ArrayList<>();
        labels.add(command.name);
        labels.addAll(command.aliases);
        for (String label : labels) {
            if (!LABELS.add(label)) {
                throw new IllegalStateException("Duplicate command name or alias: " + label);
            }
        }
        REGISTRY.put(command.name, command);
    }

    public static Collection<Cmd> all() {
        return REGISTRY.values();
    }

    public static Cmd find(String name) {
        Cmd direct = REGISTRY.get(name);
        if (direct != null) {
            return direct;
        }
        for (Cmd command : REGISTRY.values()) {
            if (command.aliases.contains(name)) {
                return command;
            }
        }
        return null;
    }

    public static void register(JavaPlugin plugin) {
        frozen = true;
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            for (Cmd command : REGISTRY.values()) {
                event.registrar().register(Brigadier.build(command), command.description, command.aliases);
            }
        });
    }

    private static final long CONFIRM_WINDOW = 15_000_000_000L;

    static void confirm(CommandSender sender) {
        Session session = session(sender);
        Session.Pending pending = session.takePending();
        if (pending == null || System.nanoTime() > pending.deadline()) {
            Msg.error(sender, "kawe.utility.nothing.confirmed");
            return;
        }
        if (pending.command().permission != null && !sender.hasPermission(pending.command().permission)) {
            Msg.error(sender, "kawe.error.no-perm", pending.command().permission);
            return;
        }
        if (!pending.selection().equals(selectionKey(session))) {
            Msg.error(sender, "kawe.error.confirm-selection-changed");
            return;
        }
        dispatch(pending.command(), pending.label(), sender, pending.input(), true);
    }

    static String selectionKey(Session session) {
        Selector selector = session.selectorRaw();
        World world = session.selectorWorld();
        if (selector == null) {
            return "";
        }
        Region region = selector.partialRegion().orElse(null);
        String shape = region == null ? "none" : region.getClass().getSimpleName() + region.min() + region.max() + region.volume();
        return (world == null ? "" : world.getUID().toString()) + selector.typeName() + shape;
    }

    public static Session session(CommandSender sender) {
        return Sessions.of(sessionId(sender));
    }

    private static UUID sessionId(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        if (sender instanceof ConsoleCommandSender) {
            return CONSOLE;
        }
        if (sender instanceof BlockCommandSender block) {
            Location at = block.getBlock().getLocation();
            return UUID.nameUUIDFromBytes(("block:" + at.getWorld().getUID() + ":" + at.getBlockX() + ":" + at.getBlockY() + ":" + at.getBlockZ())
                    .getBytes(StandardCharsets.UTF_8));
        }
        return UUID.nameUUIDFromBytes(("sender:" + sender.getClass().getName() + ":" + sender.getName()).getBytes(StandardCharsets.UTF_8));
    }

    public static void dispatch(Cmd command, String label, CommandSender sender, String input) {
        dispatch(command, label, sender, input, false);
    }

    static void dispatch(Cmd command, String label, CommandSender sender, String input, boolean confirmed) {
        if (!command.subs.isEmpty()) {
            String trimmed = input.trim();
            int space = trimmed.indexOf(' ');
            String head = space < 0 ? trimmed : trimmed.substring(0, space);
            Cmd child = head.isEmpty() ? null : command.findSub(head);
            if (child == null) {
                listSubs(command, sender);
                return;
            }
            if (child.permission != null && !sender.hasPermission(child.permission)) {
                Msg.error(sender, "kawe.error.no-perm", child.permission);
                return;
            }
            dispatch(child, label + " " + child.name, sender, space < 0 ? "" : trimmed.substring(space + 1), confirmed);
            return;
        }
        if (command.playerOnly && !(sender instanceof Player)) {
            Msg.error(sender, "kawe.error.missing-player");
            return;
        }
        Actor actor = Actor.capture(sender);
        Session session = session(sender);
        CmdContext context;
        try {
            context = bind(command, label, actor, session, tokenize(input));
            context.replayable(input, confirmed);
        } catch (InputParseException e) {
            actor.errorText(e.component());
            Msg.error(sender, "kawe.error.command.syntax", command.usage());
            return;
        }
        if (!command.queued) {
            execute(context);
            return;
        }
        session.queue(() -> Sched.async(() -> execute(context))).exceptionally(error -> {
            report(actor, error);
            return null;
        });
        int firstHop = Tuning.get().foliaFirstHopMs();
        if (firstHop > 0 && Sched.folia() && sender instanceof Player player && Bukkit.isOwnedByCurrentRegion(player)) {
            RegionQueue.linger(player.getWorld(), firstHop * 1_000_000L);
        }
    }

    static void dispatchInline(CmdContext parent, String line) throws Exception {
        dispatchInline(parent, parent.actor(), line);
    }

    static void dispatchInline(CmdContext parent, Actor actor, String line) throws Exception {
        String rest = line.trim();
        int space = rest.indexOf(' ');
        Cmd command = find((space < 0 ? rest : rest.substring(0, space)).toLowerCase(Locale.ROOT));
        if (command == null) {
            throw new CommandError(Component.text("Unknown command: " + rest));
        }
        String label = command.name;
        rest = space < 0 ? "" : rest.substring(space + 1).trim();
        while (!command.subs.isEmpty()) {
            space = rest.indexOf(' ');
            Cmd child = rest.isEmpty() ? null : command.findSub(space < 0 ? rest : rest.substring(0, space));
            if (child == null) {
                throw new CommandError(Component.text("Usage: " + command.usage()));
            }
            command = child;
            label = label + " " + child.name;
            rest = space < 0 ? "" : rest.substring(space + 1).trim();
        }
        if (command.permission != null && !actor.has(command.permission)) {
            throw new CommandError("kawe.error.no-perm", command.permission);
        }
        CmdContext context = bind(command, label, actor, parent.session(), tokenize(rest));
        context.replayable(rest, true);
        command.handler.run(context);
    }

    public static void runTool(Player player, String label, Cmd.Handler handler) {
        Actor actor = Actor.capture(player);
        Session session = session(player);
        CmdContext context = new CmdContext(Cmd.of(label).run(handler), label, actor, session, Map.of(), Set.of(), Map.of());
        session.queue(() -> Sched.async(() -> execute(context))).exceptionally(error -> {
            report(actor, error);
            return null;
        });
    }

    private static void listSubs(Cmd command, CommandSender sender) {
        List<Component> lines = new ArrayList<>();
        for (Cmd child : command.subs.values()) {
            if (child.permission == null || sender.hasPermission(child.permission)) {
                lines.add(Component.text("/" + command.name + " " + child.name, NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.suggestCommand("/" + command.name + " " + child.name + " "))
                        .append(Component.text(": " + child.description, NamedTextColor.GRAY)));
            }
        }
        Msg.print(sender, new PaginationBox("Help: " + command.name, null, lines).perPage(Math.max(1, lines.size())).create(1));
    }

    private static void execute(CmdContext context) {
        if (Debug.timings()) {
            Debug.time("execute " + context.label());
        }
        Actor actor = context.actor();
        String selectionBefore = null;
        try {
            Cmd command = context.command();
            if (command.confirm != null && !context.confirmed() && !command.confirm.passes(context, command.confirmArgument)) {
                context.session().pending(new Session.Pending(command, context.label(), context.input(), System.nanoTime() + CONFIRM_WINDOW,
                        selectionKey(context.session())));
                return;
            }
            context.start();
            selectionBefore = selectionKey(context.session());
            command.handler.run(context);
            if (Debug.timings()) {
                Debug.time("handler done " + context.label());
            }
        } catch (Exception error) {
            report(actor, error);
        } finally {
            for (Edit edit : context.edits()) {
                edit.staged();
            }
            if (selectionBefore != null && !selectionBefore.equals(selectionKey(context.session()))) {
                Preload.selected(context.session().owner());
            }
            context.session().untrack(context.edits());
            persist(context.session());
        }
    }

    private static void persist(Session session) {
        for (ChangeSet changes : session.drainUnsaved()) {
            try {
                DiskHistory.save(session.owner(), changes);
            } catch (RuntimeException e) {
                Sched.plugin().getLogger().log(Level.WARNING, "Could not save history to disk", e);
            }
        }
    }

    public static void report(Actor actor, Throwable error) {
        Throwable e = error;
        while ((e instanceof CompletionException || e instanceof ExecutionException) && e.getCause() != null) {
            e = e.getCause();
        }
        switch (e) {
            case InputParseException parse -> actor.errorText(parse.component());
            case CommandError command -> actor.errorText(command.component());
            case RegionOperationException region -> actor.errorText(region.component());
            case IncompleteRegionException ignored -> actor.error("kawe.error.incomplete-region");
            case MaxChangedBlocksException max -> actor.error("kawe.error.max-changes", max.limit());
            case EditCancelledException ignored -> actor.error("kawe.cancel.reason", Msg.tr("kawe.cancel.reason.manual"));
            case PartialCommitException partial -> {
                actor.error("kawe.error.partial-commit", partial.failedChunks());
                Sched.plugin().getLogger().log(Level.SEVERE, "KAWE edit partially applied", partial);
            }
            default -> {
                actor.error("kawe.error.unknown", String.valueOf(e.getMessage()));
                Sched.plugin().getLogger().log(Level.SEVERE, "KAWE command failed", e);
            }
        }
    }

    static List<String> tokenize(String input) throws InputParseException {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean token = false;
        Deque<Character> open = new ArrayDeque<>();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < input.length() && (input.charAt(i + 1) == '"' || input.charAt(i + 1) == '\\')) {
                    current.append(input.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' && open.isEmpty() && current.isEmpty()) {
                quoted = true;
                token = true;
                continue;
            }
            if (c == '{' || c == '[') {
                open.push(c);
            } else if (c == '}' || c == ']') {
                char expected = c == '}' ? '{' : '[';
                if (open.isEmpty() || open.pop() != expected) {
                    throw InputParseException.of("kawe.error.parse.invalid-dangling-character", String.valueOf(c));
                }
            } else if (Character.isWhitespace(c) && open.isEmpty()) {
                if (token || !current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                    token = false;
                }
                continue;
            }
            current.append(c);
        }
        if (quoted) {
            throw InputParseException.of("kawe.error.parse.invalid-dangling-character", "\"");
        }
        if (!open.isEmpty()) {
            throw InputParseException.of("kawe.error.parser.missing-rbracket");
        }
        if (token || !current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static boolean isFlagToken(String token) {
        if (token.length() < 2 || token.charAt(0) != '-') {
            return false;
        }
        for (int i = 1; i < token.length(); i++) {
            if (!Character.isLetter(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean knownFlags(Cmd command, String token) {
        for (int i = 1; i < token.length(); i++) {
            if (!command.flags.containsKey(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static CmdContext bind(Cmd command, String label, Actor actor, Session session, List<String> tokens) throws InputParseException {
        Set<Character> switches = new HashSet<>();
        Map<Character, String> flagValues = new HashMap<>();
        List<String> positional = new ArrayList<>();
        boolean greedyTail = !command.params.isEmpty() && command.params.getLast().type().greedy();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            boolean greedyStarted = greedyTail && positional.size() >= command.params.size();
            boolean greedyNext = greedyTail && positional.size() == command.params.size() - 1;
            if (isFlagToken(token) && !greedyStarted && (!greedyNext || knownFlags(command, token))) {
                for (int j = 1; j < token.length(); j++) {
                    char key = token.charAt(j);
                    Cmd.Flag flag = command.flags.get(key);
                    if (flag == null) {
                        throw InputParseException.of("kawe.error.invalid-flag", "-" + key);
                    }
                    if (switches.contains(key) || flagValues.containsKey(key)) {
                        throw InputParseException.of("kawe.error.invalid-flag", "-" + key + " (twice)");
                    }
                    if (flag.type() == null) {
                        switches.add(key);
                    } else {
                        if (j != token.length() - 1 || i + 1 >= tokens.size()) {
                            throw InputParseException.of("kawe.error.invalid-arguments", "-" + key + " <" + flag.name() + ">");
                        }
                        flagValues.put(key, tokens.get(++i));
                    }
                }
                continue;
            }
            positional.add(token);
        }
        Map<String, String> values = new HashMap<>();
        List<Cmd.Param> params = command.params;
        Cmd.Param firstSkipped = null;
        int index = 0;
        for (int p = 0; p < params.size(); p++) {
            Cmd.Param param = params.get(p);
            int requiredAfter = 0;
            for (int q = p + 1; q < params.size(); q++) {
                if (!params.get(q).optional()) {
                    requiredAfter++;
                }
            }
            int remaining = positional.size() - index;
            boolean take = remaining > 0 && (!param.optional()
                    || (remaining > requiredAfter && param.type().accepts(positional.get(index))));
            if (!take) {
                if (!param.optional()) {
                    throw InputParseException.of("kawe.error.invalid-arguments", command.usage());
                }
                if (firstSkipped == null && remaining > 0) {
                    firstSkipped = param;
                }
                values.put(param.name(), fallback(param));
                continue;
            }
            if (param.type().greedy() && p == params.size() - 1) {
                values.put(param.name(), String.join(" ", positional.subList(index, positional.size())));
                index = positional.size();
            } else {
                values.put(param.name(), positional.get(index++));
            }
        }
        if (index < positional.size()) {
            if (firstSkipped != null) {
                firstSkipped.type().parse(positional.get(index), null);
            }
            throw InputParseException.of("kawe.error.invalid-arguments", command.usage());
        }
        return new CmdContext(command, label, actor, session, values, switches, flagValues);
    }

    private static String fallback(Cmd.Param param) {
        String value = param.fallback();
        return value == null || value.isEmpty() ? null : value;
    }

    static List<String> suggestions(Cmd command, CommandSender sender, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (!command.subs.isEmpty()) {
            if (args.length == 1) {
                List<String> names = new ArrayList<>();
                for (Cmd child : command.subs.values()) {
                    if (child.permission == null || sender.hasPermission(child.permission)) {
                        names.add(child.name);
                        names.addAll(child.aliases);
                    }
                }
                return Types.filter(names.stream(), args[0]);
            }
            Cmd child = command.findSub(args[0]);
            if (child == null || (child.permission != null && !sender.hasPermission(child.permission))) {
                return List.of();
            }
            return suggestions(child, sender, Arrays.copyOfRange(args, 1, args.length));
        }
        String partial = args[args.length - 1];
        Set<Character> used = new HashSet<>();
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            String token = args[i];
            if (isFlagToken(token) && command.flags.containsKey(token.charAt(1))) {
                for (int j = 1; j < token.length(); j++) {
                    used.add(token.charAt(j));
                }
                Cmd.Flag flag = command.flags.get(token.charAt(token.length() - 1));
                if (flag != null && flag.type() != null) {
                    i++;
                }
                continue;
            }
            positional.add(token);
        }
        if (args.length >= 2 && isFlagToken(args[args.length - 2])) {
            Cmd.Flag flag = command.flags.get(args[args.length - 2].charAt(args[args.length - 2].length() - 1));
            if (flag != null && flag.type() != null) {
                return safeSuggest(flag.type(), partial);
            }
        }
        if (partial.startsWith("-") && partial.length() <= 2) {
            List<String> out = new ArrayList<>();
            for (Cmd.Flag flag : command.flags.values()) {
                if (!used.contains(flag.key())) {
                    out.add("-" + flag.key());
                }
            }
            return Types.filter(out.stream(), partial);
        }
        Cmd.Param target = targetParam(command, positional);
        return target == null ? List.of() : safeSuggest(target.type(), partial);
    }

    private static Cmd.Param targetParam(Cmd command, List<String> done) {
        List<Cmd.Param> params = command.params;
        int index = 0;
        for (int p = 0; p < params.size(); p++) {
            Cmd.Param param = params.get(p);
            if (index >= done.size()) {
                return param;
            }
            if (param.type().greedy() && p == params.size() - 1) {
                return param;
            }
            if (!param.optional() || param.type().accepts(done.get(index))) {
                index++;
            }
        }
        return null;
    }

    private static List<String> safeSuggest(ArgType<?> type, String partial) {
        try {
            return type.suggest(partial, null);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
