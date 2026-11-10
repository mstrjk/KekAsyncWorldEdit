package teacommontea.kawe.command;

import net.minecraft.world.level.block.Blocks;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.Entities;
import teacommontea.kawe.engine.EntityRecord;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.op.ClipboardOps;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.selector.CuboidSelector;
import teacommontea.kawe.region.selector.ExtendingCuboidSelector;
import teacommontea.kawe.region.selector.Selector;

import java.util.List;

public final class ClipboardCommands {

    private ClipboardCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("/copy").aliases("/cp").confirm(Confirm.REGION).perm("kawe.clipboard.copy").desc("Copy the selection to the clipboard")
                .flag('e', "entities").flag('b', "biomes").flag('c', "center").flag('m', "mask", Types.MASK)
                .run(ClipboardCommands::copy));
        Commands.add(Cmd.of("/lazycopy").perm("kawe.clipboard.lazycopy").desc("Lazily copy the selection to the clipboard")
                .flag('e', "Skip copy entities").flag('b', "Also copy biomes").run(ClipboardCommands::lazyCopy));
        Commands.add(Cmd.of("/cut").confirm(Confirm.REGION).perm("kawe.clipboard.cut").desc("Cut the selection to the clipboard")
                .opt("leavePattern", Types.PATTERN, "air").flag('e', "entities").flag('b', "biomes").flag('m', "mask", Types.MASK)
                .run(ClipboardCommands::cut));
        Commands.add(Cmd.of("/paste").aliases("/p", "/pa").perm("kawe.clipboard.paste").desc("Paste the clipboard's contents")
                .flag('a', "skip air").flag('o', "origin").flag('s', "select").flag('n', "select only").flag('e', "entities")
                .flag('b', "biomes").flag('m', "mask", Types.WORD).flag('x', "remove entities").flag('v', "ignore structure void")
                .run(c -> paste(c, false)));
        Commands.add(Cmd.of("/place").perm("kawe.clipboard.place").desc("Place the clipboard's contents without applying transformations (e.g. rotate)")
                .flag('a', "skip air").flag('o', "origin").flag('s', "select").flag('n', "select only").flag('e', "entities")
                .flag('b', "biomes").flag('x', "remove entities")
                .run(c -> paste(c, true)));
        Commands.add(Cmd.of("/rotate").perm("kawe.clipboard.rotate").desc("Rotate the contents of the clipboard").immediate()
                .arg("rotateY", Types.DOUBLE).opt("rotateX", Types.DOUBLE, "0").opt("rotateZ", Types.DOUBLE, "0")
                .run(ClipboardCommands::rotate));
        Commands.add(Cmd.of("/flip").aliases("/mirror").perm("kawe.clipboard.flip").desc("Flip the contents of the clipboard across the origin").immediate()
                .opt("direction", Types.DIRECTION, "me").run(ClipboardCommands::flip));
        Commands.add(Cmd.of("clearclipboard").aliases("/clearclipboard", "/cc", "/clearclip").perm("kawe.clipboard.clear").desc("Clear your clipboard").immediate()
                .run(c -> {
                    if (c.session().clipboard() == null) {
                        return;
                    }
                    c.session().clipboard(null);
                    c.print("kawe.clearclipboard.cleared");
                }));
    }

    private static void copy(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Mask mask = c.flagValue('m', Types.MASK);
        BlockVec origin = c.flag('c') ? region.center().toBlock().withY(region.min().y()) : c.placement();
        ClipboardOps.CopyResult result = ClipboardOps.copy(edit, region, origin, mask, c.flag('b'), c.flag('e'));
        c.session().clipboard(new ClipboardHolder(result.clipboard()));
        status(c, result);
    }

    private static void lazyCopy(CmdContext c) throws Exception {
        Region region = c.selection().copy();
        KWorld world = c.world();
        BlockVec origin = c.placement();
        boolean entities = !c.flag('e');
        boolean biomes = c.flag('b');
        c.session().clipboard(new ClipboardHolder(() -> ClipboardOps.copy(Edit.reader(world), region, origin, null, biomes, entities).clipboard()));
        c.print("kawe.copy.command.copy", region.volume());
    }

    private static void status(CmdContext c, ClipboardOps.CopyResult result) {
        c.print("kawe.operation.affected.block", result.blocks());
        c.print("kawe.operation.affected.biome", result.biomeColumns());
        c.print("kawe.operation.affected.entity", result.entities().size());
    }

    private static void cut(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Mask mask = c.flagValue('m', Types.MASK);
        Pattern leave = c.get("leavePattern", Types.PATTERN);
        BlockVec origin = c.placement();
        ClipboardOps.CopyResult result = ClipboardOps.copy(edit, region, origin, mask, c.flag('b'), c.flag('e'));
        ClipboardOps.fill(edit, region, mask, leave);
        Edit.Result committed = c.commit(edit, c.flag('e'));
        if (c.flag('e')) {
            Entities.removeById(edit.world(), result.entities());
            if (committed.history() != null) {
                committed.history().entitiesRemoved(result.entities());
            }
        }
        c.session().clipboard(new ClipboardHolder(result.clipboard()));
        if (c.tips()) {
            c.print("kawe.tips.tip.lazycut");
        }
        status(c, result);
    }

    private static void paste(CmdContext c, boolean place) throws Exception {
        ClipboardHolder holder = c.clipboard();
        Clipboard clipboard = holder.clipboard();
        boolean transformed = !place && !holder.base().isIdentity();
        String maskInput = place ? null : c.flagRaw('m');
        boolean ignoreVoid = !place && c.flag('v');
        boolean asPlace = place || (!transformed && maskInput == null && !ignoreVoid);
        ClipboardHolder effective = holder;
        if (place && !holder.base().isIdentity()) {
            effective = new ClipboardHolder(clipboard);
        }
        BlockVec to = c.flag('o') ? clipboard.origin() : c.placement();
        Edit edit = c.edit();
        Mask sourceMask = null;
        if (maskInput != null) {
            sourceMask = Masks.parse(maskInput, c.parseContext(clipboard));
        }
        if (ignoreVoid) {
            Mask notVoid = (x, y, z) -> clipboard.get(x, y, z).getBlock() != Blocks.STRUCTURE_VOID;
            sourceMask = sourceMask == null ? notVoid : sourceMask.and(notVoid);
        }
        boolean onlySelect = c.flag('n');
        Transform transform = effective.transform();
        ClipboardOps.PasteBounds bounds = ClipboardOps.bounds(clipboard, transform, to);
        long changed = 0;
        if (!onlySelect) {
            edit.stream();
            ClipboardOps.paste(edit, transform, clipboard, to, !c.flag('a'), c.flag('b'), sourceMask);
            Edit.Result committed = c.commit(edit, c.flag('x') || (c.flag('e') && !clipboard.entities().isEmpty()));
            changed = committed.changed();
            List<EntityRecord> removed = c.flag('x') ? Entities.remove(edit.world(), new CuboidRegion(bounds.min(), bounds.max()), edit.allowing(entity -> true)) : List.of();
            List<EntityRecord> added = c.flag('e') ? ClipboardOps.pasteEntities(edit, transform, clipboard, to) : List.of();
            if (committed.history() != null) {
                committed.history().entitiesRemoved(removed);
                committed.history().entitiesAdded(added);
            }
        }
        if (c.flag('s') || onlySelect) {
            Selector current = c.selector();
            Selector next = current instanceof ExtendingCuboidSelector
                    ? new ExtendingCuboidSelector(bounds.min(), bounds.max())
                    : new CuboidSelector(bounds.min(), bounds.max());
            c.session().selector(edit.world().bukkit(), next);
        }
        if (onlySelect) {
            c.print("kawe.paste.selected");
        } else if (asPlace) {
            c.print("kawe.paste.command.paste", to.toString(), changed);
        } else {
            c.print("kawe.paste.pasted", to.toString(), changed);
        }
        if (asPlace && c.tips()) {
            c.print("kawe.tips.tip.copypaste");
        }
    }

    private static void rotate(CmdContext c) throws Exception {
        ClipboardHolder holder = c.clipboard();
        double y = c.get("rotateY", Types.DOUBLE);
        double x = c.get("rotateX", Types.DOUBLE);
        double z = c.get("rotateZ", Types.DOUBLE);
        Transform transform = Transform.IDENTITY.rotateY(-y).rotateX(-x).rotateZ(-z);
        holder.transform(transform.combine(holder.base()));
        c.print("kawe.rotate.rotated");
    }

    private static void flip(CmdContext c) throws Exception {
        ClipboardHolder holder = c.clipboard();
        BlockVec direction = c.get("direction", Types.DIRECTION).abs();
        Transform transform = Transform.IDENTITY.scale(direction.multiply(-2).add(1, 1, 1).toVec3());
        holder.transform(transform.combine(holder.base()));
        c.print("kawe.flip.flipped");
    }
}
