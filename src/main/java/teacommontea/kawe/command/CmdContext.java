package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Settings;
import teacommontea.kawe.Tuning;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.engine.ChangeSet;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.EditCancelledException;
import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.engine.SideEffect;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.parse.ParseContext;
import teacommontea.kawe.parse.Transforms;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.selector.Selector;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.session.Navigation;
import teacommontea.kawe.session.Restrictions;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.text.Msg;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public final class CmdContext {

    private final List<Edit> edits = new CopyOnWriteArrayList<>();
    private final List<Edit> readers = new CopyOnWriteArrayList<>();
    private volatile long started;
    private volatile boolean worked;
    private volatile boolean timed;

    private final Cmd command;
    private final Actor actor;
    private final Session session;
    private final Map<String, String> values;
    private final Set<Character> switches;
    private final Map<Character, String> flagValues;
    private final String label;
    private Extent extent;
    private String input = "";
    private boolean confirmed;

    CmdContext(Cmd command, String label, Actor actor, Session session, Map<String, String> values, Set<Character> switches, Map<Character, String> flagValues) {
        this.command = command;
        this.label = label;
        this.actor = actor;
        this.session = session;
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
        this.switches = Set.copyOf(switches);
        this.flagValues = Map.copyOf(flagValues);
    }

    public Cmd command() {
        return command;
    }

    public String label() {
        return label;
    }

    void replayable(String raw, boolean alreadyConfirmed) {
        this.input = raw;
        this.confirmed = alreadyConfirmed;
    }

    String input() {
        return input;
    }

    boolean confirmed() {
        return confirmed;
    }

    public String commandLine() {
        return ("/" + label + " " + input).trim();
    }

    public Actor actor() {
        return actor;
    }

    public Session session() {
        return session;
    }

    public Extent extent() {
        return extent;
    }

    public ParseContext parseContext() {
        return parseContext(extent);
    }

    public ParseContext parseContext(Extent target) {
        World override = overrideWorld();
        return new ParseContext(actor, session, override != null ? KWorld.of(override) : actor.kworld(), target);
    }

    private World overrideWorld() {
        UUID id = session.worldOverride();
        return id == null ? null : Bukkit.getWorld(id);
    }

    public boolean tips() {
        return session.tips() && !actor.has("kawe.tips");
    }

    public void requireActorWorld() throws CommandError {
        UUID id = session.worldOverride();
        if (id != null && actor.kworld() != null && !id.equals(actor.kworld().bukkit().getUID())) {
            throw new CommandError("kawe.error.placement-other-world");
        }
    }

    public boolean has(String name) {
        return values.get(name) != null;
    }

    public String raw(String name) {
        return values.get(name);
    }

    public <T> T get(String name, ArgType<T> type) throws InputParseException {
        String raw = values.get(name);
        return raw == null ? null : type.parse(raw, this);
    }

    public boolean flag(char key) {
        return switches.contains(key) || flagValues.containsKey(key);
    }

    public String flagRaw(char key) {
        return flagValues.get(key);
    }

    public <T> T flagValue(char key, ArgType<T> type) throws InputParseException {
        String raw = flagValues.get(key);
        return raw == null ? null : type.parse(raw, this);
    }

    public KWorld world() throws CommandError {
        UUID id = session.worldOverride();
        World override = id == null ? null : Bukkit.getWorld(id);
        if (id != null && override == null) {
            session.worldOverride(null);
            throw new CommandError("kawe.error.world-unloaded");
        }
        KWorld world = override != null ? KWorld.of(override) : actor.kworld();
        if (world == null) {
            throw new CommandError("kawe.error.missing-world");
        }
        return world;
    }

    public Selector selector() throws CommandError {
        return session.selector(world().bukkit());
    }

    public Region selection() throws CommandError, IncompleteRegionException {
        return selector().region();
    }

    public BlockVec placement() throws CommandError, IncompleteRegionException {
        if (session.placeAtPos1()) {
            return selector().primary();
        }
        requireActorWorld();
        BlockVec at = actor.block();
        if (at == null) {
            throw new IncompleteRegionException();
        }
        return at;
    }

    public ClipboardHolder clipboard() throws CommandError {
        ClipboardHolder holder = session.clipboard();
        if (holder == null) {
            throw new CommandError("kawe.error.empty-clipboard");
        }
        return holder;
    }

    List<Edit> edits() {
        return edits;
    }

    public Edit edit() throws CommandError, InputParseException {
        KWorld world = world();
        Edit edit = new Edit(world, session.limit());
        edits.add(edit);
        session.track(edit);
        Restrictions restrictions = Restrictions.of(actor.player(), session, world.bukkit());
        if (restrictions != null) {
            if (restrictions.isEmpty()) {
                throw new CommandError("kawe.cancel.reason.no.region");
            }
            edit.restrictions(restrictions);
        }
        Set<SideEffect> effects = session.sideEffects();
        edit.sideEffects(effects);
        edit.history(!session.fast() && effects.contains(SideEffect.HISTORY));
        extent = edit;
        String mask = session.maskInput();
        if (mask != null) {
            edit.mask(Masks.parse(mask, parseContext()));
        }
        String sourceMask = session.sourceMaskInput();
        if (sourceMask != null) {
            edit.sourceMask(Masks.parse(sourceMask, parseContext()));
        }
        String transform = session.transformInput();
        if (transform != null) {
            edit.transform(Transforms.parse(transform, parseContext()));
        }
        return edit;
    }

    public Edit.Result commit(Edit edit) {
        return commit(edit, false);
    }

    public Edit reader() throws CommandError {
        Edit reader = Edit.reader(world());
        readers.add(reader);
        return reader;
    }

    void start() {
        started = System.nanoTime();
    }

    public void worked() {
        worked = true;
    }

    private Component timed(String key, Object... args) {
        if (!timing()) {
            return Msg.tr(key, args);
        }
        String template = Msg.raw(key);
        if (template.endsWith(".")) {
            template = template.substring(0, template.length() - 1);
        }
        return stamp(Msg.format(template, args));
    }

    private Component timed(Component message) {
        return timing() ? stamp(message) : message;
    }

    private Component stamp(Component message) {
        timed = true;
        return message.append(Msg.tr("kawe.timing", (System.nanoTime() - started) / 1_000_000L));
    }

    private boolean timing() {
        if (timed || started == 0L || !Tuning.get().showTiming()) {
            return false;
        }
        boolean done = worked;
        for (int i = 0; !done && i < edits.size(); i++) {
            done = edits.get(i).used();
        }
        for (int i = 0; !done && i < readers.size(); i++) {
            done = readers.get(i).used();
        }
        return done;
    }

    private void remember(ChangeSet history, boolean keepEmpty) {
        if (history != null) {
            history.command(label);
            session.remember(history, keepEmpty);
        }
    }

    public Edit.Result commit(Edit edit, boolean keepEmpty) {
        Edit.Result result;
        try {
            result = join(edit.commit());
        } catch (RuntimeException failure) {
            try {
                remember(edit.record(), keepEmpty);
            } catch (RuntimeException secondary) {
                failure.addSuppressed(secondary);
            }
            throw failure;
        }
        if (result.tileFailures() > 0) {
            actor.error("kawe.error.tile-failures", result.tileFailures());
        }
        if (!result.failed()) {
            remember(result.history(), keepEmpty);
            if (edit.stopped()) {
                throw new EditCancelledException();
            }
            return result;
        }
        PartialCommitException partial = new PartialCommitException(result.failures().size(), result.failures().getFirst());
        try {
            remember(result.history(), keepEmpty);
        } catch (RuntimeException secondary) {
            partial.addSuppressed(secondary);
        }
        throw partial;
    }

    public static <T> T join(java.util.concurrent.CompletableFuture<T> future) {
        return Sched.join(future);
    }

    public <T> T onPlayer(Supplier<T> task) throws CommandError {
        Player player = actor.player();
        if (player == null) {
            throw new CommandError("kawe.error.missing-player");
        }
        return join(Sched.onEntity(player, task));
    }

    public void checkRadius(double radius) throws CommandError {
        int max = Settings.get().maxRadius();
        if (max > 0 && radius > max) {
            throw new CommandError("kawe.error.limit.max-radius", max);
        }
    }

    public void unstuck() throws CommandError {
        if (actor.isPlayer() && Settings.get().unstuckOnGenerate()) {
            Navigation.Target target = Navigation.free(reader(), actor.block());
            if (target != null) {
                teleport(target);
            }
        }
    }

    public void teleport(Navigation.Target target) throws CommandError {
        Player player = actor.player();
        if (player == null) {
            throw new CommandError("kawe.error.missing-player");
        }
        Navigation.teleport(player, world().bukkit(), target, actor.yaw(), actor.pitch());
    }

    public void floatAt(int x, int y, int z, boolean alwaysGlass) throws CommandError {
        Player player = actor.player();
        if (player == null) {
            throw new CommandError("kawe.error.missing-player");
        }
        requireActorWorld();
        KWorld world = world();
        if (y - 1 < world.minY() || y > world.maxY()) {
            throw new CommandError("kawe.up.obstructed");
        }
        boolean mayFly = join(Sched.onEntity(player, player::getAllowFlight));
        boolean glass = alwaysGlass || !mayFly;
        if (glass) {
            BlockPos below = new BlockPos(x, y - 1, z);
            boolean standing = join(world.queue().submit(x >> 4, z >> 4, chunk -> {
                if (!Masks.blocksMovement(world.level().getBlockState(below))) {
                    world.level().setBlock(below, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL);
                }
                return Masks.blocksMovement(world.level().getBlockState(below));
            }));
            if (!standing) {
                throw new CommandError("kawe.up.obstructed");
            }
        }
        Location to = new Location(world.bukkit(), x + 0.5, y, z + 0.5, actor.yaw(), actor.pitch());
        boolean moved = join(join(Sched.onEntity(player, () -> player.teleportAsync(to))));
        if (!moved) {
            throw new CommandError("kawe.up.obstructed");
        }
        if (!glass) {
            join(Sched.onEntity(player, () -> {
                player.setFlying(true);
                return true;
            }));
        }
    }

    public void print(String key, Object... args) {
        actor.print(timed(key, args));
    }

    public void info(String key, Object... args) {
        actor.infoText(timed(key, args));
    }

    public void error(String key, Object... args) {
        actor.error(key, args);
    }

    public void print(Component component) {
        actor.print(timed(component));
    }

    public void errorText(Component component) {
        actor.errorText(component);
    }
}
