package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import teacommontea.kawe.Settings;
import teacommontea.kawe.op.Trees;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Transforms;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.tool.BrushSettings;
import teacommontea.kawe.tool.BrushTool;
import teacommontea.kawe.tool.NavigationWand;
import teacommontea.kawe.tool.SelectionWand;
import teacommontea.kawe.tool.Tool;
import teacommontea.kawe.tool.ToolTag;
import teacommontea.kawe.tool.Tools;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

public final class ToolCommands {

    private ToolCommands() {
    }

    private interface ToolFactory {
        Tool create(CmdContext c) throws Exception;
    }

    private static Cmd tool(String name, String permission, String description, String equip, ToolFactory factory) {
        return Cmd.of(name).perm(permission).desc(description).player().run(c -> bind(c, factory.create(c), equip));
    }

    private record Entry(String name, String alias, Function<String, Cmd> command) {
    }

    public static void register() {
        List<Entry> entries = List.of(
                new Entry("none", "unbind", n -> Cmd.of(n).perm("kawe.tool.none").desc("Unbind a bound tool from your current item").player()
                        .run(ToolCommands::none)),
                new Entry("selwand", "/selwand", n -> tool(n, "kawe.setwand", "Selection wand tool", "kawe.tool.selwand.equip",
                        c -> SelectionWand.INSTANCE).aliases(n.startsWith("/") ? new String[] {"/setwand"} : new String[0])),
                new Entry("navwand", "/navwand", n -> tool(n, "kawe.setwand", "Navigation wand tool", "kawe.tool.navwand.equip",
                        c -> NavigationWand.INSTANCE)),
                new Entry("info", "/info", n -> tool(n, "kawe.tool.info", "Block information tool", "kawe.tool.info.equip",
                        c -> new Tools.Info())),
                new Entry("inspect", "/inspect", n -> tool(n, "kawe.tool.inspect", "Inspect edits within a radius", "kawe.tool.inspect.equip",
                        c -> new Tools.Inspect())),
                new Entry("tree", "/tree", n -> tool(n, "kawe.tool.tree", "Tree generator tool", "kawe.tool.tree.equip",
                        c -> new Tools.TreePlanter(c.get("type", RegionCommands.TREE))).opt("type", RegionCommands.TREE, "tree")),
                new Entry("featureplacer", "/featureplacer", n -> tool(n, "kawe.tool.feature", "Feature placer tool",
                        "kawe.tool.feature.equip", c -> new Tools.Placer(c.get("feature", GenerationCommands.FEATURE), false))
                        .aliases("featuretool", "/featuretool").arg("feature", GenerationCommands.FEATURE)),
                new Entry("structureplacer", "/structureplacer", n -> tool(n, "kawe.tool.structure", "Structure placer tool",
                        "kawe.tool.structure.equip", c -> new Tools.Placer(c.get("structure", GenerationCommands.STRUCTURE), true))
                        .aliases("structuretool", "/structuretool").arg("structure", GenerationCommands.STRUCTURE)),
                new Entry("stacker", null, n -> tool(n, "kawe.tool.stack", "Block stacker tool", "kawe.tool.stack.equip",
                        ToolCommands::stacker).opt("range", Types.INT, "10").opt("mask", Types.MASK, "!#existing")),
                new Entry("repl", "/repl", n -> tool(n, "kawe.tool.replacer", "Block replacer tool", "kawe.tool.repl.equip",
                        c -> {
                            c.get("pattern", Types.PATTERN);
                            return new Tools.Replacer(c.raw("pattern"));
                        }).arg("pattern", Types.PATTERN)),
                new Entry("cycler", "/cycler", n -> tool(n, "kawe.tool.data-cycler", "Block data cycler tool", "kawe.tool.data-cycler.equip",
                        c -> new Tools.Cycler())),
                new Entry("floodfill", "/floodfill", n -> tool(n, "kawe.tool.flood-fill", "Flood fill tool", "kawe.tool.floodfill.equip",
                        ToolCommands::floodFill).aliases("flood", "/flood").arg("pattern", Types.PATTERN).arg("range", Types.INT)),
                new Entry("deltree", "/deltree", n -> tool(n, "kawe.tool.deltree", "Floating tree remover tool", "kawe.tool.deltree.equip",
                        c -> new Tools.TreeRemover())),
                new Entry("farwand", "/farwand", n -> tool(n, "kawe.tool.farwand", "Wand at a distance tool", "kawe.tool.farwand.equip",
                        c -> new Tools.FarWand())),
                new Entry("lrbuild", "/lrbuild", n -> tool(n, "kawe.tool.lrbuild", "Long-range building tool", "kawe.tool.lrbuild.equip",
                        c -> {
                            c.get("primary", Types.PATTERN);
                            c.get("secondary", Types.PATTERN);
                            return new Tools.LongRangeBuild(c.raw("primary"), c.raw("secondary"));
                        }).arg("primary", Types.PATTERN).arg("secondary", Types.PATTERN)));
        Cmd root = Cmd.of("tool").desc("Binds a tool to the item in your hand");
        for (Entry entry : entries) {
            root.sub(entry.command().apply(entry.name()));
            if (entry.alias() != null) {
                Commands.add(entry.command().apply(entry.alias()));
            }
        }
        Commands.add(root);
        Commands.add(Cmd.of("mask").aliases("/mask").perm("kawe.brush.options.mask").desc("Set the brush destination mask").player()
                .opt("mask", Types.MASK, "").run(c -> brushMask(c, false)));
        Commands.add(Cmd.of("/smask").aliases("/sourcemask", "smask", "sourcemask").perm("kawe.brush.options.mask").desc("Set the brush source mask").player()
                .opt("mask", Types.MASK, "").run(c -> brushMask(c, true)));
        Commands.add(Cmd.of("/transform").aliases("transform").perm("kawe.brush.options.transform").desc("Set the brush transform").player()
                .opt("transform", Types.TEXT, "").run(ToolCommands::transform));
        Commands.add(Cmd.of("/material").aliases("mat", "material", "pattern").perm("kawe.brush.options.material").desc("Set the brush material").player()
                .arg("pattern", Types.PATTERN).run(ToolCommands::material));
        Commands.add(Cmd.of("range").aliases("/range").perm("kawe.brush.options.range").desc("Set the brush range").player()
                .arg("range", Types.INT).run(ToolCommands::range));
        Commands.add(Cmd.of("size").perm("kawe.brush.options.size").desc("Set the brush size").player()
                .arg("size", Types.INT).run(ToolCommands::size));
        Commands.add(Cmd.of("tracemask").aliases("tarmask", "tm", "targetmask").perm("kawe.brush.options.tracemask").desc("Set the trace mask").player()
                .opt("mask", Types.MASK, "").run(ToolCommands::traceMask));
        Commands.add(Cmd.of("target").aliases("tar", "/target", "/tar").perm("kawe.brush.target").desc("Toggle between different target modes").player()
                .opt("mode", Types.INT, "0").run(ToolCommands::target));
        Commands.add(Cmd.of("targetoffset").aliases("to").perm("kawe.brush.targetoffset").desc("Set the targeting offset").player()
                .opt("offset", Types.INT, "0").run(ToolCommands::targetOffset));
        Commands.add(Cmd.of("scroll").perm("kawe.brush.scroll").desc("Set what scrolling while sneaking does").player()
                .opt("mode", SCROLL, "none").opt("values", Types.TEXT, "").run(ToolCommands::scroll));
        Commands.add(Cmd.of("primary").aliases("/primary").perm("kawe.brush.primary").desc("Set the right click brush").player()
                .arg("brush", Types.TEXT).run(c -> oneSide(c, true)));
        Commands.add(Cmd.of("secondary").aliases("/secondary").perm("kawe.brush.secondary").desc("Set the left click brush").player()
                .arg("brush", Types.TEXT).run(c -> oneSide(c, false)));
        Commands.add(Cmd.of("/").aliases(",").perm("kawe.superpickaxe").desc("Toggle the super pickaxe function").player()
                .opt("enabled", Types.BOOL, "").run(ToolCommands::togglePickaxe));
        Commands.add(Cmd.of("superpickaxe").aliases("pickaxe", "sp").desc("Super-pickaxe commands")
                .sub(Cmd.of("single").perm("kawe.superpickaxe").desc("Enable the single block super pickaxe mode").player()
                        .run(c -> pickaxe(c, new Tools.SinglePickaxe(), "kawe.tool.superpickaxe.mode.single")))
                .sub(Cmd.of("area").perm("kawe.superpickaxe.area").desc("Enable the area super pickaxe pickaxe mode").player()
                        .arg("range", Types.INT).run(ToolCommands::area))
                .sub(Cmd.of("recursive").aliases("recur").perm("kawe.superpickaxe.recursive").desc("Enable the recursive super pickaxe pickaxe mode")
                        .player().arg("range", Types.DOUBLE).run(ToolCommands::recursive)));
    }

    private static final ArgType<BrushSettings.Scroll> SCROLL = new ArgType<>() {
        @Override
        public BrushSettings.Scroll parse(String raw, CmdContext context) throws InputParseException {
            String name = raw.toUpperCase(Locale.ROOT).replace('-', '_');
            for (BrushSettings.Scroll value : BrushSettings.Scroll.values()) {
                if (value.name().equals(name) || value.name().replace("_", "").equals(name)) {
                    return value;
                }
            }
            throw new InputParseException(Component.text("Unknown scroll action: " + raw));
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Arrays.stream(BrushSettings.Scroll.values()).map(v -> v.name().toLowerCase(Locale.ROOT)), partial);
        }
    };

    static Material held(CmdContext c) throws CommandError {
        Material type = c.actor().handType();
        if (type == null || type.isAir()) {
            throw new CommandError("kawe.tool.error.cannot-bind", "air", "You need to hold an item");
        }
        if (type.isBlock()) {
            throw new CommandError("kawe.tool.error.cannot-bind", type.getKey().asString(), "Blocks can't be used");
        }
        return type;
    }

    static void unbindHint(CmdContext c) {
        c.print("kawe.tool.unbind-instruction", Component.text("/tool none").clickEvent(ClickEvent.runCommand("/tool none")));
    }

    static void tagHeld(CmdContext c, Material type) throws CommandError {
        Player player = c.actor().player();
        if (player == null) {
            return;
        }
        c.onPlayer(() -> {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType() == type) {
                ToolTag.tag(hand);
            }
            return null;
        });
    }

    static void bind(CmdContext c, Tool tool, String equip) throws CommandError {
        Material type = held(c);
        c.session().tool(type, tool);
        tagHeld(c, type);
        c.print(equip, Component.translatable(type.translationKey()));
        unbindHint(c);
    }

    static BrushTool brushTool(CmdContext c, boolean create) throws CommandError {
        Material type = held(c);
        Session session = c.session();
        Tool tool = session.tool(type);
        if (tool instanceof BrushTool brush) {
            return brush;
        }
        if (!create) {
            throw new CommandError("kawe.brush.brush.none");
        }
        BrushTool brush = new BrushTool();
        session.tool(type, brush);
        tagHeld(c, type);
        return brush;
    }

    private static void none(CmdContext c) throws CommandError {
        c.session().tool(held(c), null);
        c.print("kawe.tool.none.equip");
    }

    private static Tool stacker(CmdContext c) throws Exception {
        int range = c.get("range", Types.INT);
        if (range < 1) {
            throw new CommandError(Component.text("Range must be >= 1"));
        }
        c.checkRadius(range);
        c.get("mask", Types.MASK);
        return new Tools.Stacker(range, c.raw("mask") == null ? "!#existing" : c.raw("mask"));
    }

    private static Tool floodFill(CmdContext c) throws Exception {
        int range = c.get("range", Types.INT);
        int max = Settings.get().maxSuperPickaxe();
        if (range < 1) {
            throw new CommandError(Component.text("Range must be >= 1"));
        }
        if (max >= 0 && range > max) {
            throw new CommandError("kawe.tool.superpickaxe.max-range", max);
        }
        c.get("pattern", Types.PATTERN);
        return new Tools.FloodFill(range, c.raw("pattern"));
    }

    private static void brushMask(CmdContext c, boolean source) throws Exception {
        BrushTool tool = brushTool(c, false);
        BrushSettings settings = tool.right();
        String input = c.raw("mask");
        if (input == null) {
            if (source) {
                settings.sourceMask(null);
                c.print("kawe.brush.brush.source.mask.disabled");
            } else {
                settings.mask(null);
                c.print("kawe.tool.mask.disabled");
            }
            return;
        }
        c.get("mask", Types.MASK);
        if (source) {
            settings.sourceMask(input);
            c.print("kawe.brush.brush.source.mask");
        } else {
            settings.mask(input);
            c.print("kawe.tool.mask.set");
        }
    }

    private static void transform(CmdContext c) throws Exception {
        BrushTool tool = brushTool(c, false);
        String raw = c.raw("transform");
        if (raw == null || raw.isBlank()) {
            tool.right().transform(null);
            c.print("kawe.brush.brush.transform.disabled");
            return;
        }
        Transforms.parse(raw, c.parseContext());
        tool.right().transform(raw.trim());
        c.print("kawe.brush.brush.transform");
    }

    private static void material(CmdContext c) throws Exception {
        BrushTool tool = brushTool(c, false);
        c.get("pattern", Types.PATTERN);
        tool.right().material(c.raw("pattern"));
        c.print("kawe.tool.material.set");
    }

    private static void range(CmdContext c) throws Exception {
        int range = c.get("range", Types.INT);
        if (range < 0) {
            throw new CommandError(Component.text("Range must be >= 0"));
        }
        brushTool(c, true).range(range);
        c.print("kawe.tool.range.set");
    }

    private static void size(CmdContext c) throws Exception {
        int size = c.get("size", Types.INT);
        if (size < 0) {
            throw new CommandError(Component.text("Size must be >= 0"));
        }
        BrushTool.checkBrushRadius(size);
        brushTool(c, true).right().size(size);
        c.print("kawe.tool.size.set");
    }

    private static void traceMask(CmdContext c) throws Exception {
        BrushTool tool = brushTool(c, false);
        String input = c.raw("mask");
        if (input == null) {
            tool.traceMask(null);
            c.print("kawe.tool.tracemask.disabled");
            return;
        }
        c.get("mask", Types.MASK);
        tool.traceMask(input);
        c.print("kawe.tool.tracemask.set");
    }

    private static void target(CmdContext c) throws Exception {
        BrushTool tool = brushTool(c, false);
        BrushTool.TargetMode[] modes = BrushTool.TargetMode.values();
        BrushTool.TargetMode mode = modes[Math.floorMod(c.get("mode", Types.INT), modes.length)];
        tool.targetMode(mode);
        c.print("kawe.brush.brush.target.mode.set", mode.name());
    }

    private static void targetOffset(CmdContext c) throws Exception {
        BrushTool tool = brushTool(c, false);
        int offset = c.get("offset", Types.INT);
        tool.targetOffset(offset);
        c.print("kawe.brush.brush.target.offset.set", offset);
    }

    private static void scroll(CmdContext c) throws Exception {
        BrushTool tool = brushTool(c, false);
        BrushSettings.Scroll mode = c.get("mode", SCROLL);
        String raw = c.raw("values");
        List<String> values = raw == null || raw.isBlank() ? List.of() : List.of(raw.trim().split("\\s+"));
        if ((mode == BrushSettings.Scroll.MASK || mode == BrushSettings.Scroll.PATTERN || mode == BrushSettings.Scroll.CLIPBOARD) && values.isEmpty()) {
            throw new CommandError(Component.text("List the values to scroll through after the mode"));
        }
        for (String value : values) {
            if (mode == BrushSettings.Scroll.MASK) {
                Types.MASK.parse(value, c);
            } else if (mode == BrushSettings.Scroll.PATTERN) {
                Types.PATTERN.parse(value, c);
            }
        }
        tool.right().scroll(mode, values);
        if (mode == BrushSettings.Scroll.NONE) {
            c.print("kawe.brush.brush.scroll.action.unset");
        } else {
            c.print("kawe.brush.brush.scroll.action.set", mode.name().toLowerCase(Locale.ROOT));
        }
    }

    private static void oneSide(CmdContext c, boolean right) throws Exception {
        Material type = held(c);
        Session session = c.session();
        Tool before = session.tool(type);
        BrushTool previous = before instanceof BrushTool brush ? brush : null;
        session.tool(type, null);
        Commands.dispatchInline(c, "brush " + c.get("brush", Types.TEXT));
        Tool after = session.tool(type);
        if (after instanceof BrushTool fresh && previous != null) {
            if (right) {
                fresh.left(previous.left());
            } else {
                fresh.right(previous.right());
            }
        }
    }

    private static void togglePickaxe(CmdContext c) throws Exception {
        Session session = c.session();
        Boolean wanted = c.get("enabled", Types.BOOL);
        boolean enabled = session.superPickaxe();
        if (wanted != null && wanted == enabled) {
            c.print(enabled ? "kawe.tool.superpickaxe.enabled.already" : "kawe.tool.superpickaxe.disabled.already");
            return;
        }
        if (session.superPickaxeTool() == null) {
            session.superPickaxeTool(new Tools.SinglePickaxe());
        }
        session.superPickaxe(!enabled);
        if (!enabled) {
            tagPickaxe(c);
        }
        c.print(enabled ? "kawe.tool.superpickaxe.disabled" : "kawe.tool.superpickaxe.enabled");
    }

    private static void tagPickaxe(CmdContext c) throws CommandError {
        Material type = c.actor().handType();
        if (type != null && type.name().endsWith("_PICKAXE")) {
            tagHeld(c, type);
        }
    }

    private static void pickaxe(CmdContext c, Tool tool, String key) throws CommandError {
        c.session().superPickaxeTool(tool);
        c.session().superPickaxe(true);
        tagPickaxe(c);
        c.print(key);
    }

    private static void checkPickaxeRange(double range) throws CommandError {
        int max = Settings.get().maxSuperPickaxe();
        if (range < 0) {
            throw new CommandError(Component.text("Range must be >= 0"));
        }
        if (max >= 0 && range > max) {
            throw new CommandError("kawe.tool.superpickaxe.max-range", max);
        }
    }

    private static void area(CmdContext c) throws Exception {
        int range = c.get("range", Types.INT);
        checkPickaxeRange(range);
        pickaxe(c, new Tools.AreaPickaxe(range), "kawe.tool.superpickaxe.mode.area");
    }

    private static void recursive(CmdContext c) throws Exception {
        double range = c.get("range", Types.DOUBLE);
        checkPickaxeRange(range);
        pickaxe(c, new Tools.RecursivePickaxe(range), "kawe.tool.superpickaxe.mode.recursive");
    }

    static Trees.Kind treeKind(CmdContext c) throws Exception {
        return c.get("type", RegionCommands.TREE);
    }
}
