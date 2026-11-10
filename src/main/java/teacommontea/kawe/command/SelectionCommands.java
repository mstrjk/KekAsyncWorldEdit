package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.math.BlockVec2;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.op.Ops;
import teacommontea.kawe.parse.BlockInput;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.region.ConvexPolyhedralRegion;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.CylinderRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.RegionOperationException;
import teacommontea.kawe.region.selector.ConvexSelector;
import teacommontea.kawe.region.selector.CuboidSelector;
import teacommontea.kawe.region.selector.CylinderSelector;
import teacommontea.kawe.region.selector.EllipsoidSelector;
import teacommontea.kawe.region.selector.ExtendingCuboidSelector;
import teacommontea.kawe.region.selector.FuzzySelector;
import teacommontea.kawe.region.selector.PolygonSelector;
import teacommontea.kawe.region.selector.Selector;
import teacommontea.kawe.region.selector.Selectors;
import teacommontea.kawe.region.selector.SphereSelector;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;
import teacommontea.kawe.tool.NavigationWand;
import teacommontea.kawe.tool.SelectionWand;
import teacommontea.kawe.tool.ToolTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

public final class SelectionCommands {

    private static final ArgType<String> SELECTOR = new ArgType<>() {
        @Override
        public String parse(String raw, CmdContext context) {
            return raw.toLowerCase(Locale.ROOT);
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Stream.of("cuboid", "extend", "poly", "ellipsoid", "sphere", "cyl", "convex", "hull", "polyhedron", "polyhedral", "fuzzy",
                    "magic", "list"), partial);
        }
    };

    private SelectionCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("/pos1").aliases("/1").perm("kawe.selection.pos").desc("Set position 1")
                .opt("coordinates", Types.VECTOR, "").run(c -> position(c, true)));
        Commands.add(Cmd.of("/pos2").aliases("/2").perm("kawe.selection.pos").desc("Set position 2")
                .opt("coordinates", Types.VECTOR, "").run(c -> position(c, false)));
        Commands.add(Cmd.of("/hpos1").perm("kawe.selection.hpos").desc("Set position 1 to targeted block").player()
                .run(c -> targeted(c, true)));
        Commands.add(Cmd.of("/hpos2").perm("kawe.selection.hpos").desc("Set position 2 to targeted block").player()
                .run(c -> targeted(c, false)));
        Commands.add(Cmd.of("/chunk").perm("kawe.selection.chunk").desc("Set the selection to your current chunk.")
                .opt("coordinates", Types.VECTOR, "").flag('s', "expand").flag('c', "chunk coordinates").run(SelectionCommands::chunk));
        Commands.add(Cmd.of("/bound").perm("kawe.selection.bound").desc("Select a box around you").player()
                .arg("sizeX", Types.INT).opt("sizeY", Types.INT, "").opt("sizeZ", Types.INT, "")
                .opt("offsetX", Types.INT, "0").opt("offsetY", Types.INT, "0").opt("offsetZ", Types.INT, "0").run(SelectionCommands::bound));
        Commands.add(Cmd.of("/wand").perm("kawe.wand").desc("Get the wand object").player().immediate()
                .flag('n', "navigation").run(SelectionCommands::wand));
        Commands.add(Cmd.of("toggleeditwand").aliases("/toggleeditwand").perm("kawe.wand.toggle").player().immediate()
                .desc("Remind the user that the wand is now a tool and can be unbound with /tool none.").run(SelectionCommands::toggleWand));
        Commands.add(Cmd.of("/contract").perm("kawe.selection.contract").desc("Contract the selection area")
                .arg("amount", Types.INT).opt("reverseAmount", Types.INT, "0").opt("direction", Types.DIRECTIONS, "me")
                .run(SelectionCommands::contract));
        Commands.add(Cmd.of("/expand").perm("kawe.selection.expand").desc("Expand the selection area")
                .arg("amount", Types.WORD).opt("reverseAmount", Types.INT, "0").opt("direction", Types.DIRECTIONS, "me")
                .run(SelectionCommands::expand));
        Commands.add(Cmd.of("/shift").perm("kawe.selection.shift").desc("Shift the selection area")
                .arg("amount", Types.INT).opt("direction", Types.DIRECTIONS, "me").run(SelectionCommands::shift));
        Commands.add(Cmd.of("/outset").perm("kawe.selection.outset").desc("Outset the selection area")
                .arg("amount", Types.INT).flag('h', "horizontal").flag('v', "vertical").run(c -> outset(c, true)));
        Commands.add(Cmd.of("/inset").perm("kawe.selection.inset").desc("Inset the selection area")
                .arg("amount", Types.INT).flag('h', "horizontal").flag('v', "vertical").run(c -> outset(c, false)));
        Commands.add(Cmd.of("/size").perm("kawe.selection.size").desc("Get information about the selection").immediate()
                .flag('c', "clipboard").run(SelectionCommands::size));
        Commands.add(Cmd.of("/count").perm("kawe.analysis.count").desc("Counts the number of blocks matching a mask")
                .arg("mask", Types.MASK).run(SelectionCommands::count));
        Commands.add(Cmd.of("/distr").perm("kawe.analysis.distr").desc("Get the distribution of blocks in the selection")
                .flag('c', "Get the distribution of the clipboard instead").flag('d', "Separate blocks by state").flag('p', "Gets page from a previous distribution.", Types.INT)
                .run(SelectionCommands::distr));
        Commands.add(Cmd.of("/trim").perm("kawe.selection.trim").desc("Minimize the selection to encompass matching blocks")
                .opt("mask", Types.MASK, "#existing").run(SelectionCommands::trim));
        Commands.add(Cmd.of("/sel").aliases(";", "/desel", "/deselect").perm("kawe.analysis.sel").desc("Choose a region selector")
                .immediate().opt("selector", SELECTOR, "").flag('d', "default").run(SelectionCommands::select));
    }

    private static void position(CmdContext c, boolean primary) throws CommandError, InputParseException {
        KWorld world = c.world();
        BlockVec given = c.get("coordinates", Types.VECTOR);
        BlockVec pos;
        if (given != null) {
            pos = given.clampY(world.minY(), world.maxY());
        } else if (c.actor().isPlayer()) {
            pos = c.actor().block().clampY(world.minY(), world.maxY());
        } else {
            c.print("kawe.pos.console-require-coords");
            return;
        }
        select(c, pos, primary, "kawe.pos.already-set");
    }

    private static void select(CmdContext c, BlockVec pos, boolean primary, String alreadySetKey) throws CommandError {
        Selector selector = c.selector();
        boolean changed = primary ? selector.selectPrimary(pos) : selector.selectSecondary(pos);
        if (!changed) {
            c.print(alreadySetKey);
            return;
        }
        if (selector.needsWorld()) {
            selector.resolve(c.reader());
        }
        if (primary) {
            selector.explainPrimary(c.actor().sender(), pos);
        } else {
            selector.explainSecondary(c.actor().sender(), pos);
        }
    }

    private static void targeted(CmdContext c, boolean primary) throws CommandError {
        Player player = c.actor().player();
        BlockVec target = c.onPlayer(() -> {
            RayTraceResult hit = player.rayTraceBlocks(300, FluidCollisionMode.NEVER);
            if (hit == null || hit.getHitBlock() == null) {
                return null;
            }
            return new BlockVec(hit.getHitBlock().getX(), hit.getHitBlock().getY(), hit.getHitBlock().getZ());
        });
        if (target == null) {
            c.print("kawe.hpos.no-block");
            return;
        }
        select(c, target, primary, "kawe.hpos.already-set");
    }

    private static void chunk(CmdContext c) throws Exception {
        KWorld world = c.world();
        BlockVec min;
        BlockVec max;
        if (c.flag('s')) {
            if (c.raw("coordinates") != null) {
                throw new CommandError(Component.text("-s uses the current selection and takes no coordinates"));
            }
            Region region = c.selector().region();
            BlockVec minChunk = new BlockVec(region.min().x() >> 4, world.minY() >> 4, region.min().z() >> 4);
            BlockVec maxChunk = new BlockVec(region.max().x() >> 4, world.maxY() >> 4, region.max().z() >> 4);
            min = new BlockVec(minChunk.x() << 4, world.minY(), minChunk.z() << 4);
            max = new BlockVec((maxChunk.x() << 4) + 15, world.maxY(), (maxChunk.z() << 4) + 15);
            c.print("kawe.chunk.selected-multiple", minChunk.x(), minChunk.y(), minChunk.z(), maxChunk.x(), maxChunk.y(), maxChunk.z());
        } else {
            BlockVec given = c.get("coordinates", Types.VECTOR);
            BlockVec chunk;
            if (given != null) {
                chunk = c.flag('c') ? given : new BlockVec(given.x() >> 4, given.y() >> 4, given.z() >> 4);
            } else if (c.actor().isPlayer()) {
                BlockVec at = c.actor().block();
                chunk = new BlockVec(at.x() >> 4, at.y() >> 4, at.z() >> 4);
            } else {
                throw new CommandError(Component.text("A player or coordinates are required."));
            }
            min = new BlockVec(chunk.x() << 4, world.minY(), chunk.z() << 4);
            max = new BlockVec((chunk.x() << 4) + 15, world.maxY(), (chunk.z() << 4) + 15);
            c.print("kawe.chunk.selected", chunk.x(), chunk.y(), chunk.z());
        }
        Selector current = c.selector();
        Selector next = current instanceof ExtendingCuboidSelector ? new ExtendingCuboidSelector() : new CuboidSelector();
        next.selectPrimary(min.clampY(world.minY(), world.maxY()));
        next.selectSecondary(max.clampY(world.minY(), world.maxY()));
        c.session().selector(world.bukkit(), next);
    }

    private static void bound(CmdContext c) throws Exception {
        KWorld world = c.world();
        int sizeX = c.get("sizeX", Types.INT);
        Integer givenY = c.get("sizeY", Types.INT);
        Integer givenZ = c.get("sizeZ", Types.INT);
        int sizeY = givenY == null ? sizeX : givenY;
        int sizeZ = givenZ == null ? sizeX : givenZ;
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new CommandError(Component.text("Sizes must be at least 1."));
        }
        BlockVec at = c.actor().block();
        int cx = at.x() + c.get("offsetX", Types.INT);
        int cy = at.y() + c.get("offsetY", Types.INT);
        int cz = at.z() + c.get("offsetZ", Types.INT);
        BlockVec min = new BlockVec(cx - (sizeX - 1) / 2, cy - (sizeY - 1) / 2, cz - (sizeZ - 1) / 2);
        BlockVec max = new BlockVec(min.x() + sizeX - 1, min.y() + sizeY - 1, min.z() + sizeZ - 1);
        Selector current = c.selector();
        Selector next = current instanceof ExtendingCuboidSelector ? new ExtendingCuboidSelector() : new CuboidSelector();
        min = min.clampY(world.minY(), world.maxY());
        max = max.clampY(world.minY(), world.maxY());
        next.selectPrimary(min);
        next.selectSecondary(max);
        c.session().selector(world.bukkit(), next);
        c.print("kawe.bound.selected", min.x(), min.y(), min.z(), max.x(), max.y(), max.z(),
                (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1));
    }

    private static void wand(CmdContext c) throws CommandError {
        Player player = c.actor().player();
        Session session = c.session();
        boolean nav = c.flag('n');
        Material type = nav ? Material.COMPASS : Material.WOODEN_AXE;
        session.tool(type, nav ? NavigationWand.INSTANCE : SelectionWand.INSTANCE);
        ItemStack wandItem = new ItemStack(type, 1);
        ToolTag.tag(wandItem);
        boolean given = c.onPlayer(() -> player.getInventory().addItem(wandItem).isEmpty());
        if (!given) {
            throw new CommandError(Component.text("Your inventory is full"));
        }
        c.print(nav ? "kawe.wand.navwand.info" : "kawe.wand.selwand.info");
    }

    private static void toggleWand(CmdContext c) {
        c.print("kawe.wand.selwand.now.tool",
                Component.text("/tool none").clickEvent(ClickEvent.runCommand("/tool none")),
                Component.text("/tool selwand").clickEvent(ClickEvent.runCommand("/tool selwand")),
                Component.text("//wand").clickEvent(ClickEvent.runCommand("//wand")));
    }

    private static void contract(CmdContext c) throws Exception {
        Selector selector = c.selector();
        Region region = selector.region();
        int amount = c.get("amount", Types.INT);
        int reverse = c.get("reverseAmount", Types.INT);
        List<BlockVec> directions = c.get("direction", Types.DIRECTIONS);
        long before = region.volume();
        try {
            for (BlockVec dir : directions) {
                if (reverse == 0) {
                    region.contract(dir.multiply(amount));
                } else {
                    region.contract(dir.multiply(amount), dir.multiply(-reverse));
                }
            }
        } catch (RegionOperationException e) {
            c.actor().errorText(e.component());
            return;
        }
        selector.learn(region);
        c.print("kawe.contract.contracted", before - region.volume());
    }

    private static void expand(CmdContext c) throws Exception {
        Selector selector = c.selector();
        Region region = selector.region();
        KWorld world = c.world();
        long before = region.volume();
        String raw = c.raw("amount");
        if (raw.equalsIgnoreCase("vert")) {
            try {
                region.expand(new BlockVec(0, Math.max(0, world.maxY() - region.max().y()), 0), new BlockVec(0, -Math.max(0, region.min().y() - world.minY()), 0));
            } catch (RegionOperationException e) {
                c.actor().errorText(e.component());
                return;
            }
            selector.learn(region);
            c.print("kawe.expand.expanded.vert", region.volume() - before);
            return;
        }
        int amount = Types.INT.parse(raw, c);
        int reverse = c.get("reverseAmount", Types.INT);
        List<BlockVec> directions = c.get("direction", Types.DIRECTIONS);
        try {
            for (BlockVec dir : directions) {
                if (reverse == 0) {
                    region.expand(dir.multiply(amount));
                } else {
                    region.expand(dir.multiply(amount), dir.multiply(-reverse));
                }
            }
        } catch (RegionOperationException e) {
            c.actor().errorText(e.component());
            return;
        }
        selector.learn(region);
        c.print("kawe.expand.expanded", region.volume() - before);
    }

    private static void shift(CmdContext c) throws Exception {
        Selector selector = c.selector();
        Region region = selector.region();
        int amount = c.get("amount", Types.INT);
        try {
            for (BlockVec dir : c.get("direction", Types.DIRECTIONS)) {
                region.shift(dir.multiply(amount));
            }
        } catch (RegionOperationException e) {
            c.actor().errorText(e.component());
            return;
        }
        selector.learn(region);
        c.print("kawe.shift.shifted");
    }

    private static void outset(CmdContext c, boolean out) throws Exception {
        Selector selector = c.selector();
        Region region = selector.region();
        int amount = c.get("amount", Types.INT);
        boolean horizontal = c.flag('h');
        boolean vertical = c.flag('v');
        if (horizontal && vertical) {
            throw new CommandError(Component.text("Use only one of -h and -v"));
        }
        if (amount < 0) {
            throw new CommandError(Component.text("Amount must be >= 0"));
        }
        List<BlockVec> changes = new ArrayList<>();
        if (!horizontal) {
            changes.add(BlockVec.UNIT_Y.multiply(amount));
            changes.add(BlockVec.UNIT_MINUS_Y.multiply(amount));
        }
        if (!vertical) {
            changes.add(BlockVec.UNIT_X.multiply(amount));
            changes.add(BlockVec.UNIT_MINUS_X.multiply(amount));
            changes.add(BlockVec.UNIT_Z.multiply(amount));
            changes.add(BlockVec.UNIT_MINUS_Z.multiply(amount));
        }
        BlockVec[] array = changes.toArray(BlockVec[]::new);
        try {
            if (out) {
                region.expand(array);
            } else {
                region.contract(array);
            }
        } catch (RegionOperationException e) {
            c.actor().errorText(e.component());
            return;
        }
        selector.learn(region);
        c.print(out ? "kawe.outset.outset" : "kawe.inset.inset");
    }

    private static String extent(BlockVec min, BlockVec max) {
        return "(" + ((long) max.x() - min.x() + 1) + ", " + ((long) max.y() - min.y() + 1) + ", " + ((long) max.z() - min.z() + 1) + ")";
    }

    private static void size(CmdContext c) throws Exception {
        if (c.flag('c')) {
            ClipboardHolder holder = c.clipboard();
            for (int i = 0; i < holder.clipboards().size(); i++) {
                Clipboard clipboard = holder.clipboards().get(i);
                c.print("kawe.size.offset", holder.names().get(i), extent(clipboard.min(), clipboard.max()),
                        clipboard.min().subtract(clipboard.origin()).toString(), clipboard.volume());
            }
            Clipboard first = holder.clipboards().getFirst();
            c.print("kawe.size.size", extent(first.min(), first.max()));
            c.print("kawe.size.distance", first.max().distance(first.min()));
            c.print("kawe.size.blocks", first.volume());
            return;
        }
        Selector selector = c.selector();
        Region region = selector.region();
        c.print("kawe.size.type", selector.typeName());
        for (Component line : selector.info()) {
            c.actor().infoText(line);
        }
        c.print("kawe.size.size", extent(region.min(), region.max()));
        c.print("kawe.size.distance", region.max().distance(region.min()));
        c.print("kawe.size.blocks", region.volume());
    }

    private static void count(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Mask mask = c.get("mask", Types.MASK);
        c.print("kawe.count.counted", Ops.countBlocks(edit, region, mask));
    }

    private static void distr(CmdContext c) throws Exception {
        Integer page = c.flagValue('p', Types.INT);
        boolean states = c.flag('d');
        List<Session.Count> distribution;
        if (page == null) {
            boolean byState = states;
            Map<BlockState, LongAdder> counts = new ConcurrentHashMap<>();
            if (c.flag('c')) {
                Clipboard clipboard = c.clipboard().clipboard();
                clipboard.region().forEach((x, y, z) -> {
                    BlockState state = clipboard.get(x, y, z);
                    counts.computeIfAbsent(byState ? state : state.getBlock().defaultBlockState(), k -> new LongAdder()).increment();
                });
            } else {
                Region region = c.selection();
                Edit reader = c.reader();
                Ops.parallelChunks(reader, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
                    BlockState state = reader.get(x, y, z);
                    counts.computeIfAbsent(byState ? state : state.getBlock().defaultBlockState(), k -> new LongAdder()).increment();
                }));
            }
            distribution = new ArrayList<>();
            counts.forEach((state, count) -> distribution.add(new Session.Count(state, count.sum())));
            distribution.sort((a, b) -> Long.compare(b.amount(), a.amount()));
            c.session().lastDistribution(distribution, states);
            page = 1;
        } else {
            distribution = c.session().lastDistribution();
            states = c.session().lastDistributionStates();
            if (distribution == null) {
                c.print("kawe.distr.no-previous");
                return;
            }
        }
        if (distribution.isEmpty()) {
            c.print("kawe.distr.no-blocks");
            return;
        }
        long total = distribution.stream().mapToLong(Session.Count::amount).sum();
        boolean separate = states;
        List<Component> lines = distributionLines(distribution, separate);
        c.print("kawe.distr.total", total);
        try {
            c.print(new PaginationBox("Block Distribution", "//distr -p %page%" + (separate ? " -d" : ""), lines).perPage(7)
                    .forConsole(!c.actor().isPlayer()).create(page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    static List<Component> distributionLines(List<Session.Count> distribution, boolean separate) {
        long total = distribution.stream().mapToLong(Session.Count::amount).sum();
        List<Component> lines = new ArrayList<>();
        for (Session.Count count : distribution) {
            double percent = count.amount() * 100.0 / total;
            Component name = Component.translatable(count.state().getBlock().getDescriptionId());
            String id = BuiltInRegistries.BLOCK.getKey(count.state().getBlock()).toString();
            if (separate && count.state() != count.state().getBlock().defaultBlockState()) {
                name = name.append(Component.text("*")).hoverEvent(HoverEvent.showText(Component.text(BlockInput.name(count.state()))));
            } else {
                name = name.hoverEvent(HoverEvent.showText(Component.text(id)));
            }
            lines.add(Component.text(String.format("%7.3f%%  ", percent), NamedTextColor.GOLD)
                    .append(Component.text(count.amount() + "  ", NamedTextColor.YELLOW)).append(name));
        }
        return lines;
    }

    private static void trim(CmdContext c) throws Exception {
        Region original = c.selection();
        Edit edit = c.edit();
        Mask mask = c.get("mask", Types.MASK);
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Object lock = new Object();
        Ops.parallelChunks(edit, original, key -> {
            int[] local = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            original.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
                if (mask.test(x, y, z)) {
                    local[0] = Math.min(local[0], x);
                    local[1] = Math.min(local[1], y);
                    local[2] = Math.min(local[2], z);
                    local[3] = Math.max(local[3], x);
                    local[4] = Math.max(local[4], y);
                    local[5] = Math.max(local[5], z);
                }
            });
            synchronized (lock) {
                for (int i = 0; i < 3; i++) {
                    bounds[i] = Math.min(bounds[i], local[i]);
                    bounds[i + 3] = Math.max(bounds[i + 3], local[i + 3]);
                }
            }
        });
        if (bounds[3] == Integer.MIN_VALUE) {
            c.error("kawe.trim.no-blocks");
            return;
        }
        BlockVec newMin = new BlockVec(bounds[0], bounds[1], bounds[2]);
        BlockVec newMax = new BlockVec(bounds[3], bounds[4], bounds[5]);
        Selector next = c.selector() instanceof ExtendingCuboidSelector ? new ExtendingCuboidSelector(newMin, newMax) : new CuboidSelector(newMin, newMax);
        c.session().selector(c.world().bukkit(), next);
        c.print("kawe.trim.trim");
    }

    private static void select(CmdContext c) throws Exception {
        KWorld world = c.world();
        String choice = c.get("selector", SELECTOR);
        Selector old = c.selector();
        if (choice == null) {
            if (c.flag('d')) {
                throw new CommandError(Component.text("-d needs a selector type"));
            }
            old.clear();
            c.print("kawe.select.cleared");
            return;
        }
        if (c.flag('d')) {
            String type = Selectors.canonical(choice);
            if (type == null) {
                listSelectors(c);
                return;
            }
            c.session().defaultSelector(type);
            c.print("kawe.select.default-set", type);
        }
        Selector next = convert(choice, old, world, c);
        if (next == null) {
            listSelectors(c);
            return;
        }
        c.session().selector(world.bukkit(), next);
    }

    private static Selector convert(String choice, Selector old, KWorld world, CmdContext c) {
        Region previous = old.partialRegion().orElse(null);
        switch (choice) {
            case "cuboid" -> {
                c.print("kawe.select.cuboid.message");
                if (old instanceof CuboidSelector cuboid && previous instanceof CuboidRegion r) {
                    return new CuboidSelector(r.pos1(), r.pos2());
                }
                return previous == null ? new CuboidSelector() : new CuboidSelector(previous.min(), previous.max());
            }
            case "extend" -> {
                c.print("kawe.select.extend.message");
                return previous == null ? new ExtendingCuboidSelector() : new ExtendingCuboidSelector(previous.min(), previous.max());
            }
            case "poly" -> {
                c.print("kawe.select.poly.message");
                List<BlockVec2> points = previous == null ? List.of() : previous.polygonize(-1);
                if (points.stream().distinct().count() < 3) {
                    return new PolygonSelector(world.minY(), world.maxY(), -1);
                }
                return new PolygonSelector(points, previous.min().y(), previous.max().y(), world.minY(), world.maxY(), -1);
            }
            case "ellipsoid" -> {
                c.print("kawe.select.ellipsoid.message");
                if (previous == null) {
                    return new EllipsoidSelector();
                }
                BlockVec center = center(previous);
                return new EllipsoidSelector(center, halfExtents(previous, center));
            }
            case "sphere" -> {
                c.print("kawe.select.sphere.message");
                if (previous == null) {
                    return new SphereSelector();
                }
                BlockVec center = center(previous);
                Vec3 radius = halfExtents(previous, center);
                return new SphereSelector(center, Math.max(radius.x(), Math.max(radius.y(), radius.z())));
            }
            case "cyl" -> {
                c.print("kawe.select.cyl.message");
                if (previous == null) {
                    return new CylinderSelector();
                }
                if (previous instanceof CylinderRegion cyl) {
                    return new CylinderSelector(cyl.center().toBlock(), cyl.radiusX(), cyl.radiusZ(), cyl.minY(), cyl.maxY());
                }
                BlockVec center = center(previous);
                Vec3 radius = halfExtents(previous, center);
                return new CylinderSelector(center, radius.x(), radius.z(), previous.min().y(), previous.max().y());
            }
            case "fuzzy", "magic" -> {
                c.print("kawe.selection.sel.fuzzy");
                c.print("kawe.selection.sel.list");
                return new FuzzySelector();
            }
            case "convex", "hull", "polyhedron", "polyhedral" -> {
                c.print(choice.equals("polyhedral") ? "kawe.selection.sel.convex.polyhedral" : "kawe.select.convex.message");
                if (previous instanceof ConvexPolyhedralRegion convex) {
                    return new ConvexSelector(convex.vertices(), -1);
                }
                List<BlockVec> corners = new ArrayList<>();
                if (previous != null) {
                    for (BlockVec2 point : previous.polygonize(-1)) {
                        corners.add(new BlockVec(point.x(), previous.min().y(), point.z()));
                        corners.add(new BlockVec(point.x(), previous.max().y(), point.z()));
                    }
                }
                if (corners.stream().distinct().count() < 4) {
                    return new ConvexSelector(-1);
                }
                return new ConvexSelector(corners.stream().distinct().toList(), -1);
            }
            default -> {
                return null;
            }
        }
    }

    private static BlockVec center(Region region) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        return new BlockVec((int) Math.floorDiv((long) min.x() + max.x(), 2), (int) Math.floorDiv((long) min.y() + max.y(), 2), (int) Math.floorDiv((long) min.z() + max.z(), 2));
    }

    private static Vec3 halfExtents(Region region, BlockVec center) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        return new Vec3(Math.max((long) max.x() - center.x(), (long) center.x() - min.x()),
                Math.max((long) max.y() - center.y(), (long) center.y() - min.y()),
                Math.max((long) max.z() - center.z(), (long) center.z() - min.z()));
    }

    private static void listSelectors(CmdContext c) {
        c.print(Component.text("Selection modes").color(NamedTextColor.YELLOW));
        String[][] modes = {
                {"cuboid", "kawe.select.cuboid.description"},
                {"extend", "kawe.select.extend.description"},
                {"poly", "kawe.select.poly.description"},
                {"ellipsoid", "kawe.select.ellipsoid.description"},
                {"sphere", "kawe.select.sphere.description"},
                {"cyl", "kawe.select.cyl.description"},
                {"convex", "kawe.select.convex.description"},
                {"polyhedral", "kawe.selection.sel.polyhedral"},
                {"fuzzy", "kawe.selection.sel.fuzzy-instruction"}
        };
        for (String[] mode : modes) {
            c.print(Component.text(mode[0]).color(NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.runCommand("//sel " + mode[0]))
                    .append(Component.text(": ").color(NamedTextColor.GRAY))
                    .append(Msg.tr(mode[1]).color(NamedTextColor.WHITE)));
        }
    }
}
