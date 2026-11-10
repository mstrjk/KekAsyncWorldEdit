package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import teacommontea.kawe.Settings;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.Entities;
import teacommontea.kawe.engine.EntityRecord;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionException;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.op.Ops;
import teacommontea.kawe.op.UtilityOps;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.CylinderRegion;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.List;
import java.util.function.Predicate;

public final class UtilityCommands {

    private static final int VERTICAL_HEIGHT = 256;

    static final ArgType<Double> NUMBER_EXPRESSION = (raw, context) -> {
        double value;
        try {
            value = Expression.compile(raw).evaluate(new double[0], context.session().timeout());
        } catch (ExpressionException e) {
            throw InputParseException.of("kawe.error.parser.invalid-expression", e.getMessage());
        }
        if (!Double.isFinite(value)) {
            throw InputParseException.of("kawe.error.parser.invalid-expression", raw + " is not a finite number");
        }
        return value;
    };

    private UtilityCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("/cancel").perm("kawe.cancel").desc("Cancel your current command").immediate()
                .run(c -> c.print("kawe.cancel.count", c.session().cancel())));
        Commands.add(Cmd.of("/calculate").aliases("/calc", "/eval", "/evaluate", "/solve").perm("kawe.calc")
                .desc("Evaluate a mathematical expression").arg("input", Types.TEXT).run(UtilityCommands::calc));
        Commands.add(Cmd.of("/confirm").perm("kawe.confirm").desc("Confirm a command").immediate()
                .run(c -> Commands.confirm(c.actor().sender())));
        Commands.add(Cmd.of("/fill").perm("kawe.fill").desc("Fill a hole")
                .arg("pattern", Types.PATTERN).arg("radius", NUMBER_EXPRESSION).opt("depth", Types.INT, "1").opt("direction", Types.DIRECTION, "down")
                .run(UtilityCommands::fill));
        Commands.add(Cmd.of("/fillr").perm("kawe.fill.recursive").desc("Fill a hole recursively")
                .arg("pattern", Types.PATTERN).arg("radius", NUMBER_EXPRESSION).opt("depth", Types.INT, "").run(UtilityCommands::fillr));
        Commands.add(Cmd.of("/drain").perm("kawe.drain").desc("Drain a pool")
                .arg("radius", NUMBER_EXPRESSION).flag('w', "Also un-waterlog blocks").flag('p', "Also remove water plants").run(UtilityCommands::drain));
        Commands.add(Cmd.of("fixlava").aliases("/fixlava").perm("kawe.fixlava").desc("Fix lava to be stationary")
                .arg("radius", Types.DOUBLE).run(c -> fixLiquid(c, true)));
        Commands.add(Cmd.of("fixwater").aliases("/fixwater").perm("kawe.fixwater").desc("Fix water to be stationary")
                .arg("radius", Types.DOUBLE).run(c -> fixLiquid(c, false)));
        Commands.add(Cmd.of("removeabove").aliases("/removeabove").perm("kawe.removeabove").desc("Remove blocks above your head.")
                .opt("size", Types.INT, "1").opt("height", Types.INT, "").run(c -> removeVertical(c, true)));
        Commands.add(Cmd.of("removebelow").aliases("/removebelow").perm("kawe.removebelow").desc("Remove blocks below you.")
                .opt("size", Types.INT, "1").opt("height", Types.INT, "").run(c -> removeVertical(c, false)));
        Commands.add(Cmd.of("removenear").aliases("/removenear").perm("kawe.removenear").desc("Remove blocks near you.")
                .arg("mask", Types.MASK).opt("radius", Types.INT, "50").run(UtilityCommands::removeNear));
        Commands.add(Cmd.of("replacenear").aliases("/replacenear").perm("kawe.replacenear").desc("Replace nearby blocks")
                .arg("radius", Types.INT).opt("from", Types.MASK, "").arg("to", Types.PATTERN).flag('e', "Also remove the entities in the replaced blocks")
                .run(UtilityCommands::replaceNear));
        Commands.add(Cmd.of("snow").aliases("/snow").perm("kawe.snow").desc("Simulates snow")
                .opt("size", Types.DOUBLE, "10").opt("height", Types.INT, String.valueOf(VERTICAL_HEIGHT)).flag('s', "Stack snow layers")
                .run(UtilityCommands::snow));
        Commands.add(Cmd.of("thaw").aliases("/thaw").perm("kawe.thaw").desc("Thaws the area")
                .opt("size", Types.DOUBLE, "10").opt("height", Types.INT, String.valueOf(VERTICAL_HEIGHT)).run(UtilityCommands::thaw));
        Commands.add(Cmd.of("green").aliases("/green").perm("kawe.green").desc("Converts dirt to grass blocks in the area")
                .opt("size", Types.DOUBLE, "10").opt("height", Types.INT, String.valueOf(VERTICAL_HEIGHT)).flag('f', "Also convert coarse dirt")
                .run(UtilityCommands::green));
        Commands.add(Cmd.of("extinguish").aliases("/ex", "/ext", "/extinguish", "ex", "ext").perm("kawe.extinguish").desc("Extinguish nearby fire")
                .opt("radius", Types.INT, "").run(UtilityCommands::extinguish));
        Commands.add(Cmd.of("butcher").aliases("/butcher").confirm(Confirm.WORLD, "radius").perm("kawe.butcher").desc("Kill all or nearby mobs")
                .opt("radius", Types.INT, "").flag('p', "Also kill pets (any tamed mob)").flag('n', "Also kill NPCs").flag('g', "Also kill golems")
                .flag('a', "Also kill animals (untamed, not water mobs)").flag('b', "Also kill ambient mobs").flag('t', "Also kill mobs with name tags (needed on top of the mob's own flag)")
                .flag('f', "Also kill all friendly mobs (Applies the flags `-abgnptw`)").flag('r', "Also destroy armor stands")
                .flag('w', "Also kill water mobs (untamed)").run(UtilityCommands::butcher));
        Commands.add(Cmd.of("remove").aliases("rem", "rement", "/remove", "/rem", "/rement").confirm(Confirm.WORLD, "radius").perm("kawe.remove").desc("Remove entities of a type (all means every listed type)")
                .arg("type", EntityFilters.REMOVABLE).arg("radius", Types.INT).run(UtilityCommands::remove));
    }

    private static void calc(CmdContext c) throws Exception {
        String input = c.get("input", Types.TEXT);
        Expression expression;
        try {
            expression = Expression.compile(input);
        } catch (ExpressionException e) {
            c.print("kawe.calc.invalid.with-error", input, e.getMessage());
            return;
        }
        double result;
        try {
            result = expression.evaluate(new double[0], c.session().timeout());
        } catch (ExpressionException e) {
            c.errorText(Component.text(e.getMessage()));
            return;
        }
        DecimalFormat format = (DecimalFormat) NumberFormat.getInstance(c.actor().locale());
        format.applyPattern("#,##0.#####");
        String formatted = Double.isNaN(result) ? "NaN" : format.format(result);
        c.actor().infoText(Component.text(input + " = ", NamedTextColor.GRAY).append(Component.text(formatted, NamedTextColor.LIGHT_PURPLE)
                .hoverEvent(HoverEvent.showText(Component.text(Double.toString(result))))));
    }

    private static BlockVec placementInWorld(CmdContext c, Edit edit) throws Exception {
        BlockVec pos = c.placement();
        if (pos.y() < edit.minY() || pos.y() > edit.maxY()) {
            throw new CommandError("kawe.cancel.reason.world.limit", pos.y());
        }
        return pos;
    }

    private static double radius(CmdContext c, double floor) throws Exception {
        double radius = c.get("radius", NUMBER_EXPRESSION);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        radius = Math.max(floor, radius);
        c.checkRadius(radius);
        return radius;
    }

    private static int positive(CmdContext c, String name, int value) throws CommandError {
        if (value < 1) {
            throw new CommandError(Component.text(name + " must be >= 1"));
        }
        c.checkRadius(value);
        return value;
    }

    private static void fill(CmdContext c) throws Exception {
        double radius = radius(c, 1);
        int depth = positive(c, "Depth", c.get("depth", Types.INT));
        BlockVec direction = c.get("direction", Types.DIRECTION);
        Edit edit = c.edit();
        BlockVec pos = placementInWorld(c, edit);
        long affected = UtilityOps.fill(edit, pos, c.get("pattern", Types.PATTERN), radius, depth, direction);
        c.commit(edit);
        c.print("kawe.fill.created", affected);
    }

    private static void fillr(CmdContext c) throws Exception {
        double radius = radius(c, 1);
        Integer requested = c.get("depth", Types.INT);
        int depth = requested == null ? Integer.MAX_VALUE : positive(c, "Depth", requested);
        Edit edit = c.edit();
        BlockVec pos = placementInWorld(c, edit);
        long affected = UtilityOps.fillXZ(edit, pos, c.get("pattern", Types.PATTERN), radius, depth, true);
        c.commit(edit);
        c.print("kawe.fillr.created", affected);
    }

    private static void drain(CmdContext c) throws Exception {
        double radius = radius(c, 0);
        Edit edit = c.edit();
        BlockVec pos = placementInWorld(c, edit);
        long affected = UtilityOps.drain(edit, pos, radius, c.flag('w'), c.flag('p'));
        c.commit(edit);
        c.print("kawe.drain.drained", affected);
    }

    private static void fixLiquid(CmdContext c, boolean lava) throws Exception {
        double radius = Math.max(0, c.get("radius", Types.DOUBLE));
        c.checkRadius(radius);
        Edit edit = c.edit();
        BlockVec pos = placementInWorld(c, edit);
        long affected = UtilityOps.fixLiquid(edit, pos, radius, lava ? Blocks.LAVA : Blocks.WATER);
        c.commit(edit);
        c.print(lava ? "kawe.fixlava.fixed" : "kawe.fixwater.fixed", affected);
    }

    private static void removeVertical(CmdContext c, boolean above) throws Exception {
        int size = positive(c, "Size", c.get("size", Types.INT));
        Edit edit = c.edit();
        int worldHeight = edit.maxY() - edit.minY() + 1;
        Integer requested = c.get("height", Types.INT);
        int height = requested == null ? worldHeight : (int) Math.min(worldHeight, (long) positive(c, "Height", requested) + 1);
        BlockVec pos = placementInWorld(c, edit);
        long affected = above ? UtilityOps.removeAbove(edit, pos, size, height) : UtilityOps.removeBelow(edit, pos, size, height);
        c.commit(edit);
        c.print(above ? "kawe.removeabove.removed" : "kawe.removebelow.removed", affected);
    }

    private static void removeNear(CmdContext c) throws Exception {
        int radius = Math.max(1, c.get("radius", Types.INT));
        c.checkRadius(radius);
        Edit edit = c.edit();
        Mask mask = c.get("mask", Types.MASK);
        long affected = UtilityOps.removeNear(edit, c.placement(), mask, radius);
        c.commit(edit);
        c.print("kawe.removenear.removed", affected);
    }

    private static void replaceNear(CmdContext c) throws Exception {
        int radius = positive(c, "Radius", c.get("radius", Types.INT));
        Edit edit = c.edit();
        BlockVec base = c.placement();
        Mask from = c.has("from") ? c.get("from", Types.MASK) : Masks.existing(edit);
        BlockVec low;
        BlockVec high;
        try {
            low = new BlockVec(Math.subtractExact(base.x(), radius), Math.subtractExact(base.y(), radius), Math.subtractExact(base.z(), radius));
            high = new BlockVec(Math.addExact(base.x(), radius), Math.addExact(base.y(), radius), Math.addExact(base.z(), radius));
        } catch (ArithmeticException e) {
            throw new CommandError(Component.text("The radius reaches past the coordinate limit"));
        }
        CuboidRegion area = UtilityOps.clampY(edit, low, high);
        long affected = area == null ? 0 : Ops.replaceBlocks(edit, area, from, c.get("to", Types.PATTERN));
        if (area == null) {
            c.commit(edit);
        } else {
            RegionCommands.commitRemovingEntities(c, edit, area);
        }
        c.print("kawe.replacenear.replaced", affected);
    }

    private static void snow(CmdContext c) throws Exception {
        double size = Math.max(1, c.get("size", Types.DOUBLE));
        int height = positive(c, "Height", c.get("height", Types.INT));
        c.checkRadius(size);
        Edit edit = c.edit();
        BlockVec pos = c.placement();
        int low = (int) Math.max(edit.minY(), (long) pos.y() - height);
        int high = (int) Math.min(edit.maxY(), (long) pos.y() + height);
        long affected = UtilityOps.snow(edit, new CylinderRegion(pos, size, size, low, high), c.flag('s'));
        c.commit(edit);
        c.print("kawe.snow.created", affected);
    }

    private static void thaw(CmdContext c) throws Exception {
        double size = Math.max(1, c.get("size", Types.DOUBLE));
        int height = positive(c, "Height", c.get("height", Types.INT));
        c.checkRadius(size);
        Edit edit = c.edit();
        long affected = UtilityOps.thaw(edit, c.placement(), size, height);
        c.commit(edit);
        c.print("kawe.thaw.removed", affected);
    }

    private static void green(CmdContext c) throws Exception {
        double size = Math.max(1, c.get("size", Types.DOUBLE));
        int height = positive(c, "Height", c.get("height", Types.INT));
        c.checkRadius(size);
        Edit edit = c.edit();
        long affected = UtilityOps.green(edit, c.placement(), size, height, !c.flag('f'));
        c.commit(edit);
        c.print("kawe.green.changed", affected);
    }

    private static void extinguish(CmdContext c) throws Exception {
        int max = Settings.get().maxRadius();
        Integer requested = c.get("radius", Types.INT);
        int size = requested != null ? Math.max(1, requested) : max > 0 ? Math.min(40, max) : 40;
        c.checkRadius(size);
        Edit edit = c.edit();
        Mask fire = (x, y, z) -> edit.get(x, y, z).is(BlockTags.FIRE);
        long affected = UtilityOps.removeNear(edit, c.placement(), fire, size);
        c.commit(edit);
        c.print("kawe.extinguish.removed", affected);
    }

    private static List<EntityRecord> removeEntities(CmdContext c, int radius, Predicate<org.bukkit.entity.Entity> filter) throws Exception {
        Edit edit = c.edit();
        Predicate<net.minecraft.world.entity.Entity> test = edit.allowing(entity -> filter.test(entity.getBukkitEntity()));
        CylinderRegion area = radius >= 0 ? new CylinderRegion(c.placement(), radius, radius, edit.minY(), edit.maxY()) : null;
        Edit.Result committed = c.commit(edit, true);
        List<EntityRecord> removed = area != null ? Entities.remove(edit.world(), area, test) : Entities.removeAll(edit.world(), test);
        if (committed.history() != null) {
            committed.history().entitiesRemoved(removed);
        }
        return removed;
    }

    private static void butcher(CmdContext c) throws Exception {
        Settings settings = Settings.get();
        Integer requested = c.get("radius", Types.INT);
        int radius;
        if (requested == null) {
            radius = settings.butcherDefaultRadius();
            if (radius < -1) {
                throw new CommandError(Component.text("limits.butcher-default-radius is invalid (" + radius + "); use -1 or more"));
            }
        } else if (requested < -1) {
            c.print("kawe.butcher.explain-all");
            return;
        } else {
            radius = requested;
        }
        int max = settings.maxButcherRadius();
        if (max != -1 && (radius == -1 || radius > max)) {
            radius = max;
            c.errorText(Component.text("The radius was limited to " + max));
        }
        int flags = c.flag('f') ? EntityFilters.FRIENDLY : 0;
        flags |= c.flag('p') ? EntityFilters.PETS : 0;
        flags |= c.flag('n') ? EntityFilters.NPCS : 0;
        flags |= c.flag('g') ? EntityFilters.GOLEMS : 0;
        flags |= c.flag('a') ? EntityFilters.ANIMALS : 0;
        flags |= c.flag('b') ? EntityFilters.AMBIENT : 0;
        flags |= c.flag('t') ? EntityFilters.TAGGED : 0;
        flags |= c.flag('r') ? EntityFilters.ARMOR_STAND : 0;
        flags |= c.flag('w') ? EntityFilters.WATER : 0;
        int allowed = EntityFilters.permitted(c.actor(), flags);
        if (allowed != flags) {
            c.errorText(Component.text("No permission for -" + EntityFilters.letters(flags & ~allowed) + "; those mobs were left alone"));
        }
        List<EntityRecord> killed = removeEntities(c, radius, entity -> EntityFilters.butcherable(entity, allowed));
        c.print("kawe.butcher.killed", killed.size(), radius);
    }

    private static void remove(CmdContext c) throws Exception {
        int radius = c.get("radius", Types.INT);
        if (radius < -1) {
            c.print("kawe.remove.explain-all");
            return;
        }
        if (radius >= 0) {
            c.checkRadius(radius);
        }
        EntityFilters.Removable type = c.get("type", EntityFilters.REMOVABLE);
        List<EntityRecord> removed = removeEntities(c, radius, type::matches);
        c.print("kawe.remove.removed", removed.size());
    }
}
