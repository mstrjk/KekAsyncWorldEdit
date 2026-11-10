package teacommontea.kawe.tool;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import teacommontea.kawe.Sched;
import teacommontea.kawe.command.CmdContext;
import teacommontea.kawe.command.HistoryCommands;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.history.DiskHistory;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.op.Flood;
import teacommontea.kawe.op.Generation;
import teacommontea.kawe.op.Trees;
import teacommontea.kawe.parse.BlockInput;
import teacommontea.kawe.parse.BlockMatcher;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.parse.Patterns;
import teacommontea.kawe.schematic.Legacy;
import teacommontea.kawe.text.Msg;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Tools {

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

    private Tools() {
    }

    private abstract static class BlockTool implements Tool {
        private final String permission;

        BlockTool(String permission) {
            this.permission = permission;
        }

        @Override
        public String permission() {
            return permission;
        }

        @Override
        public boolean rightClick(ToolClick click) {
            if (click.block() == null) {
                return false;
            }
            click.run(label(), c -> use(c, click));
            return true;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return false;
        }

        abstract String label();

        abstract void use(CmdContext c, ToolClick click) throws Exception;
    }

    static boolean bedrockGuard(CmdContext c, BlockState state) {
        return state.getBlock() == Blocks.BEDROCK && !c.actor().has("kawe.override.bedrock");
    }

    public static final class Info extends BlockTool {
        public Info() {
            super("kawe.tool.info");
        }

        @Override
        String label() {
            return "info";
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            BlockVec at = click.block();
            KWorld world = KWorld.of(click.player().getWorld());
            int[] light = Sched.join(world.queue().submit(at.x() >> 4, at.z() >> 4, chunk -> new int[]{
                    world.level().getBrightness(LightLayer.BLOCK, new BlockPos(at.x(), at.y(), at.z())),
                    world.level().getBrightness(LightLayer.BLOCK, new BlockPos(at.x(), at.y() + 1, at.z()))}));
            Edit reader = Edit.reader(world);
            BlockState state = reader.get(at.x(), at.y(), at.z());
            String text = BlockInput.name(state);
            int legacy = Legacy.combined(state);
            c.print(Component.text("@" + at + ": ", NamedTextColor.BLUE)
                    .append(Component.translatable(state.getBlock().getDescriptionId(), NamedTextColor.YELLOW))
                    .append(Component.text(" (" + text + ") ", NamedTextColor.GRAY)
                            .hoverEvent(HoverEvent.showText(Msg.tr("kawe.tool.info.blockstate.hover")))
                            .clickEvent(ClickEvent.copyToClipboard(text)))
                    .append(Component.text(" (" + Block.getId(state) + ") ", NamedTextColor.DARK_GRAY)
                            .hoverEvent(HoverEvent.showText(Msg.tr("kawe.tool.info.internalid.hover"))))
                    .append(Component.text(" (" + (legacy >> 4) + ":" + (legacy & 0xF) + ") ", NamedTextColor.DARK_GRAY)
                            .hoverEvent(HoverEvent.showText(Msg.tr("kawe.tool.info.legacy.hover"))))
                    .append(Component.text(" (" + light[0] + "/" + light[1] + ")", NamedTextColor.WHITE)
                            .hoverEvent(HoverEvent.showText(Msg.tr("kawe.tool.info.light.hover")))));
        }
    }

    public static final class Inspect implements Tool {

        @Override
        public String permission() {
            return "kawe.tool.inspect";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            if (click.block() == null) {
                return false;
            }
            click.run("inspect", c -> inspect(c, click.block()));
            return true;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            if (click.block() == null || click.face() == null) {
                return false;
            }
            BlockVec at = click.block();
            BlockVec adjacent = new BlockVec(at.x() + click.face().getModX(), at.y() + click.face().getModY(), at.z() + click.face().getModZ());
            click.run("inspect", c -> inspect(c, adjacent));
            return true;
        }

        private static void inspect(CmdContext c, BlockVec at) throws Exception {
            if (!DiskHistory.enabled()) {
                c.print("kawe.error.setting.disable", "history.use-disk");
                return;
            }
            KWorld world = c.world();
            int count = 0;
            for (DiskHistory.Entry entry : DiskHistory.edits(world.bukkit().getName(), null, Long.MIN_VALUE,
                    new int[]{at.x(), at.y(), at.z(), at.x(), at.y(), at.z()}, DiskHistory.State.ACTIVE)) {
                int[] change = DiskHistory.load(entry, world).changeAt(at.x(), at.y(), at.z());
                if (change == null) {
                    continue;
                }
                count++;
                c.print(Msg.tr("kawe.tool.tool.inspect.info", DiskHistory.name(entry.owner()), BlockInput.name(Block.stateById(change[0])),
                                BlockInput.name(Block.stateById(change[1])), HistoryCommands.age(System.currentTimeMillis() - entry.time()))
                        .hoverEvent(HoverEvent.showText(Component.text("/tool inspect", NamedTextColor.GOLD)))
                        .clickEvent(ClickEvent.runCommand("//history summary " + entry.owner() + " " + entry.index())));
            }
            c.print("kawe.tool.tool.inspect.info.footer", count);
        }
    }

    public static final class TreePlanter extends BlockTool {
        private final Trees.Kind kind;

        public TreePlanter(Trees.Kind kind) {
            super("kawe.tool.tree");
            this.kind = kind;
        }

        @Override
        String label() {
            return "tree";
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec at = click.block().add(0, 1, 0);
            long planted = 0;
            for (int i = 0; i < 10 && planted == 0; i++) {
                planted = Trees.plant(edit, Map.of(at, kind));
            }
            c.commit(edit);
            if (planted == 0) {
                c.print("kawe.tool.tree.obstructed");
            }
        }
    }

    public static final class Placer extends BlockTool {
        private final Identifier id;
        private final boolean structure;

        public Placer(Identifier id, boolean structure) {
            super(structure ? "kawe.tool.structure" : "kawe.tool.feature");
            this.id = id;
            this.structure = structure;
        }

        @Override
        String label() {
            return structure ? "structureplacer" : "featureplacer";
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec at = click.block().add(0, 1, 0);
            int placed = structure ? Generation.structure(edit, id, at) : Generation.feature(edit, id, at);
            if (placed == Generation.UNOWNED) {
                c.errorText(Component.text("It reaches into an area another server thread owns"));
                return;
            }
            if (placed <= 0) {
                c.error(structure ? "kawe.generate.structure.failed" : "kawe.generate.feature.failed");
                return;
            }
            c.commit(edit);
        }
    }

    public static final class Stacker implements Tool {
        private final int range;
        private final String mask;

        public Stacker(int range, String mask) {
            this.range = range;
            this.mask = mask;
        }

        @Override
        public String permission() {
            return "kawe.tool.stack";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            if (click.block() == null || click.face() == null) {
                return false;
            }
            int dx = click.face().getModX();
            int dy = click.face().getModY();
            int dz = click.face().getModZ();
            click.run("stacker", c -> {
                Edit edit = c.edit();
                edit.captureTiles();
                Mask allowed = Masks.parse(mask, c.parseContext(edit));
                BlockVec from = click.block();
                BlockState state = edit.get(from.x(), from.y(), from.z());
                CompoundTag tile = state.hasBlockEntity() ? edit.tile(from.x(), from.y(), from.z()) : null;
                int x = from.x();
                int y = from.y();
                int z = from.z();
                for (int i = 0; i < range; i++) {
                    x += dx;
                    y += dy;
                    z += dz;
                    if (!allowed.test(x, y, z)) {
                        break;
                    }
                    edit.set(x, y, z, state, tile);
                }
                c.commit(edit);
            });
            return true;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return false;
        }
    }

    public static final class Replacer implements Tool {
        private volatile String pattern;
        private volatile BlockState captured;
        private volatile CompoundTag capturedTile;

        public Replacer(String pattern) {
            this.pattern = pattern;
        }

        @Override
        public String permission() {
            return "kawe.tool.replacer";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            if (click.block() == null) {
                return false;
            }
            click.run("repl", c -> {
                Edit edit = c.edit();
                BlockVec at = click.block();
                BlockState state = captured;
                if (state != null) {
                    edit.set(at.x(), at.y(), at.z(), state, capturedTile);
                } else {
                    Patterns.parse(pattern, c.parseContext(edit)).apply(edit, at.x(), at.y(), at.z());
                }
                c.commit(edit);
            });
            return true;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            if (click.block() == null) {
                return false;
            }
            click.run("repl", c -> {
                Edit reader = c.reader();
                reader.captureTiles();
                BlockVec at = click.block();
                BlockState state = reader.get(at.x(), at.y(), at.z());
                capturedTile = state.hasBlockEntity() ? reader.tile(at.x(), at.y(), at.z()) : null;
                captured = state;
                c.print("kawe.tool.repl.switched", Component.translatable(state.getBlock().getDescriptionId()));
            });
            return true;
        }
    }

    public static final class Cycler implements Tool {
        private final Map<UUID, String> selected = new ConcurrentHashMap<>();

        @Override
        public String permission() {
            return "kawe.tool.data-cycler";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            return cycle(click, true);
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return cycle(click, false);
        }

        private boolean cycle(ToolClick click, boolean forward) {
            if (click.block() == null) {
                return false;
            }
            UUID owner = click.player().getUniqueId();
            click.run("cycler", c -> {
                Edit edit = c.edit();
                BlockVec at = click.block();
                BlockState state = edit.get(at.x(), at.y(), at.z());
                List<Property<?>> properties = new ArrayList<>(state.getProperties());
                if (properties.isEmpty()) {
                    c.print("kawe.tool.data-cycler.cant-cycle");
                    return;
                }
                Property<?> current = state.getBlock().getStateDefinition().getProperty(selected.getOrDefault(owner, ""));
                if (current == null) {
                    current = properties.getFirst();
                    selected.put(owner, current.getName());
                }
                if (!forward) {
                    Property<?> next = properties.get((properties.indexOf(current) + 1) % properties.size());
                    selected.put(owner, next.getName());
                    c.print("kawe.tool.data-cycler.cycling", next.getName());
                    return;
                }
                BlockState next = advance(state, current);
                edit.set(at.x(), at.y(), at.z(), next);
                c.commit(edit);
                c.print("kawe.tool.data-cycler.new-value", current.getName(), BlockMatcher.valueName(next, current));
            });
            return true;
        }

        private static <T extends Comparable<T>> BlockState advance(BlockState state, Property<T> property) {
            List<T> values = List.copyOf(property.getPossibleValues());
            int index = values.indexOf(state.getValue(property));
            return state.setValue(property, values.get((index + 1) % values.size()));
        }
    }

    public static final class FloodFill extends BlockTool {
        private final int range;
        private final String pattern;

        public FloodFill(int range, String pattern) {
            super("kawe.tool.flood-fill");
            this.range = range;
            this.pattern = pattern;
        }

        @Override
        String label() {
            return "floodfill";
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec origin = click.block();
            BlockState initial = edit.get(origin.x(), origin.y(), origin.z());
            if (initial.isAir() || bedrockGuard(c, initial)) {
                return;
            }
            Pattern fill = Patterns.parse(pattern, c.parseContext(edit));
            Block type = initial.getBlock();
            flood(edit, origin, range, type, fill);
            c.commit(edit);
        }
    }

    static void flood(Edit edit, BlockVec origin, double radius, Block type, Pattern fill) {
        double limit = radius * radius;
        Flood.run(edit, List.of(origin), Flood.AXES, Integer.MAX_VALUE, (fx, fy, fz, x, y, z) -> {
            double dx = x - origin.x();
            double dy = y - origin.y();
            double dz = z - origin.z();
            return dx * dx + dy * dy + dz * dz <= limit && edit.get(x, y, z).getBlock() == type;
        }, (x, y, z) -> fill.apply(edit, x, y, z));
    }

    public static final class TreeRemover extends BlockTool {
        private static final int RANGE_SQ = 100 * 100;

        public TreeRemover() {
            super("kawe.tool.deltree");
        }

        @Override
        String label() {
            return "deltree";
        }

        private static boolean treeBlock(BlockState state) {
            Block block = state.getBlock();
            return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || block == Blocks.RED_MUSHROOM_BLOCK || block == Blocks.BROWN_MUSHROOM_BLOCK
                    || block == Blocks.MUSHROOM_STEM || block == Blocks.VINE || block == Blocks.NETHER_WART_BLOCK || block == Blocks.WARPED_WART_BLOCK
                    || block == Blocks.CRIMSON_STEM || block == Blocks.WARPED_STEM;
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec origin = click.block();
            if (!treeBlock(edit.get(origin.x(), origin.y(), origin.z()))) {
                c.print("kawe.tool.deltree.not-tree");
                return;
            }
            Set<BlockVec> tree = new HashSet<>();
            ArrayDeque<BlockVec> queue = new ArrayDeque<>();
            queue.add(origin);
            tree.add(origin);
            while (!queue.isEmpty()) {
                BlockVec at = queue.poll();
                for (int[] face : FACES) {
                    BlockVec next = at.add(face[0], face[1], face[2]);
                    long dx = next.x() - origin.x();
                    long dy = next.y() - origin.y();
                    long dz = next.z() - origin.z();
                    if (dx * dx + dy * dy + dz * dz > RANGE_SQ || tree.contains(next)) {
                        continue;
                    }
                    BlockState state = edit.get(next.x(), next.y(), next.z());
                    if (state.isAir() || state.getBlock() == Blocks.SNOW) {
                        continue;
                    }
                    if (!treeBlock(state)) {
                        c.print("kawe.tool.deltree.not-floating");
                        return;
                    }
                    tree.add(next);
                    queue.add(next);
                }
            }
            for (BlockVec at : tree) {
                edit.set(at.x(), at.y(), at.z(), Blocks.AIR.defaultBlockState());
            }
            c.commit(edit);
        }
    }

    public static final class FarWand extends BrushTool {
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

        private boolean select(ToolClick click, boolean primary) {
            click.run("farwand", c -> {
                c.requireActorWorld();
                BlockVec target = trace(c, c.reader(), false);
                if (target == null) {
                    c.print("kawe.tool.no-block");
                    return;
                }
                var selector = c.selector();
                if (primary ? selector.selectPrimary(target) : selector.selectSecondary(target)) {
                    if (selector.needsWorld()) {
                        selector.resolve(c.reader());
                    }
                    if (primary) {
                        selector.explainPrimary(c.actor().sender(), target);
                    } else {
                        selector.explainSecondary(c.actor().sender(), target);
                    }
                }
            });
            return true;
        }
    }

    public static final class LongRangeBuild extends BrushTool {
        private final String left;
        private final String right;

        public LongRangeBuild(String left, String right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public String permission() {
            return "kawe.tool.lrbuild";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            return build(click, right);
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return build(click, left);
        }

        private boolean build(ToolClick click, String input) {
            click.run("lrbuild", c -> {
                Edit edit = c.edit();
                Pattern pattern = Patterns.parse(input, c.parseContext(edit));
                BlockVec hit = trace(c, edit, false);
                if (hit == null) {
                    c.print("kawe.tool.no-block");
                    return;
                }
                BlockState applied = pattern.state(hit.x(), hit.y(), hit.z());
                BlockVec at = applied == null || applied.isAir() ? hit : trace(c, edit, true);
                if (at != null) {
                    pattern.apply(edit, at.x(), at.y(), at.z());
                }
                c.commit(edit);
            });
            return true;
        }
    }

    public static final class SinglePickaxe extends BlockTool {
        public SinglePickaxe() {
            super("kawe.superpickaxe");
        }

        @Override
        String label() {
            return "superpickaxe";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            return false;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return super.rightClick(click);
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec at = click.block();
            if (bedrockGuard(c, edit.get(at.x(), at.y(), at.z()))) {
                return;
            }
            edit.set(at.x(), at.y(), at.z(), Blocks.AIR.defaultBlockState());
            c.commit(edit);
        }
    }

    public static final class AreaPickaxe extends BlockTool {
        private final int range;

        public AreaPickaxe(int range) {
            super("kawe.superpickaxe.area");
            this.range = range;
        }

        @Override
        String label() {
            return "superpickaxe";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            return false;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return super.rightClick(click);
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec o = click.block();
            BlockState initial = edit.get(o.x(), o.y(), o.z());
            if (initial.isAir() || bedrockGuard(c, initial)) {
                return;
            }
            Block type = initial.getBlock();
            for (int x = o.x() - range; x <= o.x() + range; x++) {
                for (int y = Math.max(edit.minY(), o.y() - range); y <= Math.min(edit.maxY(), o.y() + range); y++) {
                    for (int z = o.z() - range; z <= o.z() + range; z++) {
                        if (edit.get(x, y, z).getBlock() == type) {
                            edit.set(x, y, z, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
            c.commit(edit);
        }
    }

    public static final class RecursivePickaxe extends BlockTool {
        private final double range;

        public RecursivePickaxe(double range) {
            super("kawe.superpickaxe.recursive");
            this.range = range;
        }

        @Override
        String label() {
            return "superpickaxe";
        }

        @Override
        public boolean rightClick(ToolClick click) {
            return false;
        }

        @Override
        public boolean leftClick(ToolClick click) {
            return super.rightClick(click);
        }

        @Override
        void use(CmdContext c, ToolClick click) throws Exception {
            Edit edit = c.edit();
            BlockVec origin = click.block();
            BlockState initial = edit.get(origin.x(), origin.y(), origin.z());
            if (initial.isAir() || bedrockGuard(c, initial)) {
                return;
            }
            Block type = initial.getBlock();
            flood(edit, origin, range, type, Pattern.of(Blocks.AIR.defaultBlockState()));
            c.commit(edit);
        }
    }
}
