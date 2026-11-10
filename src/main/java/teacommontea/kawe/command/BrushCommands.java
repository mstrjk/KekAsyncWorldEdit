package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Settings;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.Entities;
import teacommontea.kawe.engine.EntityRecord;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionException;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.op.ClipboardOps;
import teacommontea.kawe.op.Generation;
import teacommontea.kawe.op.ItemUse;
import teacommontea.kawe.op.Ops;
import teacommontea.kawe.op.RegionOps;
import teacommontea.kawe.op.Shapes;
import teacommontea.kawe.op.Trees;
import teacommontea.kawe.op.UtilityOps;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.CylinderRegion;
import teacommontea.kawe.region.EllipsoidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.selector.CuboidSelector;
import teacommontea.kawe.schematic.Schematics;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;
import teacommontea.kawe.tool.Brush;
import teacommontea.kawe.tool.BrushSettings;
import teacommontea.kawe.tool.BrushTool;
import teacommontea.kawe.tool.BrushUse;
import teacommontea.kawe.tool.Brushes;
import teacommontea.kawe.tool.HeightImages;
import teacommontea.kawe.tool.TerrainBrushes;

import java.awt.image.BufferedImage;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

public final class BrushCommands {

    enum Shape {
        CUBOID, SPHERE, CYLINDER;

        Region at(BlockVec center, double size) {
            return switch (this) {
                case CUBOID -> new CuboidRegion(center.subtract((int) size, (int) size, (int) size), center.add((int) size, (int) size, (int) size));
                case SPHERE -> new EllipsoidRegion(center, new Vec3(size, size, size));
                case CYLINDER -> new CylinderRegion(center, size, size, center.y(), center.y());
            };
        }
    }

    static final ArgType<Shape> SHAPE = new ArgType<>() {
        @Override
        public Shape parse(String raw, CmdContext context) throws InputParseException {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "cuboid", "cube" -> Shape.CUBOID;
                case "sphere" -> Shape.SPHERE;
                case "cyl", "cylinder" -> Shape.CYLINDER;
                default -> throw new InputParseException(Component.text("Unknown shape: " + raw + " (cuboid, sphere, cyl)"));
            };
        }

        @Override
        public boolean accepts(String raw) {
            return List.of("cuboid", "cube", "sphere", "cyl", "cylinder").contains(raw.toLowerCase(Locale.ROOT));
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Stream.of("cuboid", "sphere", "cyl"), partial);
        }
    };

    private BrushCommands() {
    }

    private static Cmd brush(String name, String permission, String description) {
        return Cmd.of(name).perm(permission).desc(description).player();
    }

    private static final ArgType<Double> RADIUS = UtilityCommands.NUMBER_EXPRESSION;

    public static void register() {
        Commands.add(Cmd.of("brush").aliases("br", "/brush", "/br").desc("Brushing commands")
                .sub(brush("sphere", "kawe.brush.sphere", "Choose the sphere brush").aliases("s")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "2").flag('h', "Create hollow spheres instead")
                        .flag('f', "Create falling spheres instead").run(BrushCommands::sphere))
                .sub(brush("cylinder", "kawe.brush.cylinder", "Choose the cylinder brush").aliases("cyl", "c")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "2").opt("height", Types.INT, "1").opt("thickness", Types.DOUBLE, "0")
                        .flag('h', "Create hollow cylinders instead").run(BrushCommands::cylinder))
                .sub(brush("set", "kawe.brush.set", "Set brush, sets all blocks in the area")
                        .arg("shape", SHAPE).opt("radius", RADIUS, "5").arg("pattern", Types.PATTERN).run(BrushCommands::set))
                .sub(brush("smooth", "kawe.brush.smooth", "Choose the terrain softener brush")
                        .opt("radius", RADIUS, "2").opt("iterations", Types.INT, "4").opt("mask", Types.MASK, "").run(BrushCommands::smooth))
                .sub(brush("snowsmooth", "kawe.brush.snowsmooth", "Choose the snow terrain softener brush")
                        .opt("radius", RADIUS, "2").opt("iterations", Types.INT, "1").flag('l', "The number of snow blocks under snow", Types.INT)
                        .flag('m', "The mask of blocks to use for the heightmap", Types.MASK).run(BrushCommands::snowSmooth))
                .sub(brush("gravity", "kawe.brush.gravity", "Gravity brush, simulates the effect of gravity").aliases("grav")
                        .opt("radius", RADIUS, "5").flag('h', "Affect blocks starting at the bottom of the world").run(BrushCommands::gravity))
                .sub(brush("butcher", "kawe.brush.butcher", "Butcher brush, kills mobs within a radius").aliases("kill")
                        .opt("radius", RADIUS, "5").flag('p', "Also kill pets").flag('n', "Also kill NPCs").flag('g', "Also kill golems")
                        .flag('a', "Also kill animals").flag('b', "Also kill ambient mobs").flag('t', "Also kill mobs with name tags")
                        .flag('f', "Also kill all friendly mobs (Applies the flags `-abgnptw`)").flag('r', "Also destroy armor stands")
                        .flag('w', "Also kill water mobs").run(BrushCommands::butcher))
                .sub(brush("extinguish", "kawe.brush.ex", "Shortcut fire extinguisher brush").aliases("ex")
                        .opt("radius", RADIUS, "5").run(BrushCommands::extinguish))
                .sub(brush("clipboard", "kawe.brush.clipboard", "Paste your clipboard at the brush location")
                        .flag('a', "Don't paste air from the clipboard").flag('o', "Paste starting at the target location, instead of centering")
                        .flag('e', "Paste entities if available").flag('b', "Paste biomes if available")
                        .flag('m', "Skip blocks matching this mask in the clipboard", Types.TEXT).flag('r', "Apply a random rotation")
                        .run(BrushCommands::clipboard))
                .sub(brush("deform", "kawe.brush.deform", "Deform brush, applies an expression to an area")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").opt("expression", Types.TEXT, "y-=0.2")
                        .flag('r', "Use the game's coordinate origin").flag('o', "Use the placement position as the origin").run(BrushCommands::deform))
                .sub(brush("forest", "kawe.brush.forest", "Forest brush, creates a forest in the area")
                        .arg("shape", SHAPE).opt("radius", RADIUS, "5").opt("density", Types.DOUBLE, "20").arg("type", RegionCommands.TREE)
                        .run(BrushCommands::forest))
                .sub(brush("raise", "kawe.brush.raise", "Raise brush, raise all blocks by one")
                        .arg("shape", SHAPE).opt("radius", RADIUS, "5").run(c -> shift(c, "y-=1", "raise")))
                .sub(brush("lower", "kawe.brush.lower", "Lower brush, lower all blocks by one")
                        .arg("shape", SHAPE).opt("radius", RADIUS, "5").run(c -> shift(c, "y+=1", "lower")))
                .sub(brush("snow", "kawe.brush.snow", "Snow brush, sets snow in the area")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").flag('s', "Stack snow layers").run(BrushCommands::snow))
                .sub(brush("biome", "kawe.brush.biome", "Biome brush, sets biomes in the area")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").arg("biome", BiomeCommands.BIOME)
                        .flag('c', "Whether to set the full column").run(BrushCommands::biome))
                .sub(brush("feature", "kawe.brush.feature", "Feature brush, paints Minecraft generation features")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").opt("density", Types.DOUBLE, "5").arg("type", GenerationCommands.FEATURE)
                        .run(c -> features(c, false)))
                .sub(brush("structure", "kawe.brush.feature", "Structure brush, paints Minecraft generation structures")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").opt("density", Types.DOUBLE, "5").arg("type", GenerationCommands.STRUCTURE)
                        .run(c -> features(c, true)))
                .sub(brush("line", "kawe.brush.line", "Create lines").aliases("l")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "0").flag('h', "Create only a shell").flag('s', "Selects the clicked point after drawing")
                        .flag('f', "Create a flat line").run(c -> equip(c, "line", new Brushes.Line(c.flag('h'), c.flag('s'), c.flag('f')), "kawe.brush.line",
                                radius(c, "radius"), pattern(c))))
                .sub(brush("spline", "kawe.brush.spline", "Join multiple objects together in a curve").aliases("spl", "curve")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "25")
                        .run(c -> equip(c, "spline", new Brushes.SplinePoints(), "kawe.brush.spline", radius(c, "radius"), pattern(c))))
                .sub(brush("sweep", "kawe.brush.sweep", "Sweep your clipboard content along a curve").aliases("sw", "vaesweep")
                        .opt("copies", Types.INT, "-1").run(c -> {
                            c.clipboard();
                            equip(c, "sweep", new Brushes.Sweep(c.get("copies", Types.INT)), "kawe.brush.sweep", 0, null);
                        }))
                .sub(brush("catenary", "kawe.brush.spline", "Create a hanging line between two points").aliases("cat", "gravityline", "saggedline")
                        .arg("pattern", Types.PATTERN).opt("lengthFactor", Types.DOUBLE, "1.2").opt("radius", RADIUS, "0").flag('h', "Create only a shell")
                        .flag('s', "Select the clicked point after drawing").flag('d', "Override the curve direction with your facing")
                        .run(BrushCommands::catenary))
                .sub(brush("surfacespline", "kawe.brush.surfacespline", "Draws a spline on the surface").aliases("sspline", "sspl")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "0").opt("tension", Types.DOUBLE, "0").opt("bias", Types.DOUBLE, "0")
                        .opt("continuity", Types.DOUBLE, "0").opt("quality", Types.DOUBLE, "10").run(BrushCommands::surfaceSpline))
                .sub(brush("circle", "kawe.brush.sphere", "Creates a circle, which revolves around your facing direction")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "5").opt("filled", Types.BOOL, "false")
                        .run(c -> equip(c, "circle", Brushes.circle(c.get("filled", Types.BOOL)), "kawe.brush.sphere", radius(c, "radius"), pattern(c))))
                .sub(brush("recursive", "kawe.brush.recursive", "Set all connected blocks").aliases("recurse", "r")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "5").flag('d', "Apply in depth first order")
                        .run(c -> equip(c, "recursive", Brushes.recurse(c.flag('d')), "kawe.brush.recursive", radius(c, "radius"), pattern(c))))
                .sub(brush("splatter", "kawe.brush.splatter", "Splatter a pattern on a surface")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "5").opt("points", Types.DOUBLE, "1").opt("recursion", Types.DOUBLE, "5")
                        .opt("solid", Types.BOOL, "true").run(BrushCommands::splatter))
                .sub(brush("scatter", "kawe.brush.scatter", "Scatter a pattern on a surface")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "5").opt("points", Types.DOUBLE, "5").opt("distance", Types.DOUBLE, "1")
                        .flag('o', "Overlay the block").run(BrushCommands::scatter))
                .sub(brush("layer", "kawe.brush.layer", "Replaces terrain with a layer")
                        .arg("radius", RADIUS).arg("patterns", Types.TEXT).run(BrushCommands::layer))
                .sub(brush("surface", "kawe.brush.surface", "Use a height map to paint a surface").aliases("surf")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "5")
                        .run(c -> equip(c, "surface", Brushes.surfaceSphere(), "kawe.brush.surface", radius(c, "radius"), pattern(c))))
                .sub(brush("shatter", "kawe.brush.shatter", "Creates random lines to break the terrain into pieces").aliases("partition", "split")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "10").opt("count", Types.INT, "10").run(BrushCommands::shatter))
                .sub(brush("populateschematic", "kawe.brush.populateschematic", "Scatter a schematic on a surface")
                        .aliases("populateschem", "popschem", "pschem", "ps").arg("clipboard", Types.WORD).opt("mask", Types.MASK, "")
                        .opt("radius", RADIUS, "30").opt("density", Types.DOUBLE, "50").flag('r', "Apply random rotation").run(BrushCommands::populate))
                .sub(brush("copypaste", "kawe.brush.copy", "Copy Paste brush").aliases("cp", "copypasta")
                        .opt("radius", RADIUS, "5").flag('r', "Apply random rotation on paste").flag('a', "Apply auto view based rotation on paste")
                        .run(c -> {
                            c.session().clipboard(null);
                            equip(c, "copypaste", new Brushes.CopyPaste(c.flag('r'), c.flag('a')), "kawe.brush.copy", radius(c, "radius"), null);
                        }))
                .sub(brush("blendball", "kawe.brush.blendball", "Smooths and blends terrain").aliases("bb", "blend")
                        .opt("radius", RADIUS, "5").opt("minFreqDiff", Types.INT, "1").flag('a', "Only change air and non air")
                        .flag('m', "Mask of blocks to blend", Types.MASK).run(BrushCommands::blendBall))
                .sub(brush("erode", "kawe.brush.erode", "Erodes terrain")
                        .opt("radius", RADIUS, "5").opt("erodefaces", Types.INT, "2").opt("erodeRec", Types.INT, "1").opt("fillFaces", Types.INT, "5")
                        .opt("fillRec", Types.INT, "1").run(c -> morph(c, "erode", "kawe.brush.erode", "erodefaces", "erodeRec", "fillFaces", "fillRec")))
                .sub(brush("pull", "kawe.brush.pull", "Pull terrain towards you")
                        .opt("radius", RADIUS, "5").opt("erodefaces", Types.INT, "6").opt("erodeRec", Types.INT, "0").opt("fillFaces", Types.INT, "1")
                        .opt("fillRec", Types.INT, "1").run(c -> morph(c, "pull", "kawe.brush.pull", "erodefaces", "erodeRec", "fillFaces", "fillRec")))
                .sub(brush("morph", "kawe.brush.morph", "Morph brush, morphs blocks in the area")
                        .opt("radius", RADIUS, "5").opt("minErodeFaces", Types.INT, "3").opt("numErodeIterations", Types.INT, "1")
                        .opt("minDilateFaces", Types.INT, "3").opt("numDilateIterations", Types.INT, "1")
                        .run(c -> morph(c, "morph", "kawe.brush.morph", "minErodeFaces", "numErodeIterations", "minDilateFaces", "numDilateIterations")))
                .sub(brush("dilate", "kawe.brush.morph", "Dilate preset for morph brush, dilates blocks in the area")
                        .opt("radius", RADIUS, "5").run(c -> equip(c, "dilate", TerrainBrushes.morph(5, 1, 2, 1), "kawe.brush.morph", radius(c, "radius"), null)))
                .sub(brush("height", "kawe.brush.height", "Raise or lower terrain using a heightmap").aliases("heightmap")
                        .opt("radius", RADIUS, "5").opt("yscale", Types.DOUBLE, "1").opt("image", Types.WORD, "").opt("rotation", Types.INT, "0")
                        .flag('r', "Random off-axis rotation").flag('l', "Work on snow layers").flag('s', "Disables smoothing").run(c -> terrain(c, "height", false, TerrainBrushes.cone())))
                .sub(brush("cliff", "kawe.brush.height", "Cliff brush").aliases("flatcylinder")
                        .opt("radius", RADIUS, "5").opt("image", Types.WORD, "").opt("rotation", Types.INT, "0").opt("yscale", Types.DOUBLE, "1")
                        .flag('r', "Enables random off-axis rotation").flag('l', "Will work on snow layers").flag('s', "Disables smoothing").run(c -> terrain(c, "cliff", true, TerrainBrushes.cylinder())))
                .sub(brush("flatten", "kawe.brush.height", "This brush raises or lowers land towards the clicked point").aliases("flatmap", "flat")
                        .opt("radius", RADIUS, "5").opt("image", Types.WORD, "").opt("rotation", Types.INT, "0").opt("yscale", Types.DOUBLE, "1")
                        .flag('r', "Enables random off-axis rotation").flag('l', "Will work on snow layers").flag('s', "Disables smoothing").run(c -> terrain(c, "flatten", true, TerrainBrushes.cone())))
                .sub(brush("stencil", "kawe.brush.stencil", "Use a height map to paint a surface")
                        .arg("pattern", Types.PATTERN).opt("radius", RADIUS, "5").opt("image", Types.WORD, "").opt("rotation", Types.INT, "0")
                        .opt("yscale", Types.DOUBLE, "1").flag('w', "Apply at maximum saturation").flag('r', "Apply random rotation").run(BrushCommands::stencil))
                .sub(brush("command", "kawe.brush.command", "Command brush, runs commands at the clicked point").aliases("cmd")
                        .arg("radius", RADIUS).arg("commands", Types.TEXT).flag('h', "Hide any command output").run(BrushCommands::commandBrush))
                .sub(brush("scattercommand", "kawe.brush.scattercommand", "Run commands at random points on a surface")
                        .aliases("scattercmd", "scmd", "scommand").arg("radius", RADIUS).opt("points", Types.DOUBLE, "1").opt("distance", Types.DOUBLE, "1")
                        .arg("commands", Types.TEXT).flag('p', "Show any command output").run(BrushCommands::scatterCommand))
                .sub(brush("savebrush", "kawe.brush.save", "Save your current brush").aliases("save")
                        .arg("name", Types.WORD).flag('g', "Save the brush globally").run(BrushCommands::save))
                .sub(brush("loadbrush", "kawe.brush.load", "Load a brush").aliases("load")
                        .arg("name", Types.WORD).run(BrushCommands::load))
                .sub(Cmd.of("listbrush").aliases("/listbrush").perm("kawe.brush.list").desc("List saved brushes")
                        .flag('p', "Page", Types.INT).run(BrushCommands::list))
                .sub(brush("apply", "kawe.brush.apply", "Apply brush, apply a function to every block")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").arg("type", APPLY_TYPE).opt("value", Types.TEXT, "")
                        .run(c -> applyBrush(c, false)))
                .sub(brush("paint", "kawe.brush.paint", "Paint brush, apply a function to a surface")
                        .arg("shape", SHAPE).opt("radius", Types.DOUBLE, "5").opt("density", Types.DOUBLE, "20").arg("type", APPLY_TYPE).opt("value", Types.TEXT, "")
                        .run(c -> applyBrush(c, true)))
                .sub(brush("image", "kawe.brush.image", "Use a picture to paint a surface")
                        .arg("image", Types.WORD).opt("radius", RADIUS, "5").flag('a', "Use image alpha").run(BrushCommands::imageBrush))
                .sub(brush("rock", "kawe.brush.rock", "Creates a distorted sphere").aliases("blob")
                        .arg("pattern", Types.PATTERN).opt("radius", Types.RADII, "10").opt("roundness", Types.DOUBLE, "100")
                        .opt("frequency", Types.DOUBLE, "30").opt("amplitude", Types.DOUBLE, "50").run(BrushCommands::rock)));
    }

    static double radius(CmdContext c, String name) throws Exception {
        double radius = c.get(name, RADIUS);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        BrushTool.checkBrushRadius(radius);
        return radius;
    }

    static void equip(CmdContext c, String name, Brush brush, String permission, double size, String material) throws Exception {
        BrushTool tool = ToolCommands.brushTool(c, true);
        BrushSettings old = tool.right();
        String label = "brush " + name;
        BrushSettings settings = new BrushSettings();
        if (old.brush() != null && old.label().equals(label)) {
            settings.mask(old.mask());
            settings.sourceMask(old.sourceMask());
            settings.scroll(old.scroll(), old.scrollValues());
        }
        settings.brush(brush, label, permission);
        settings.line(label + (c.input() == null || c.input().isBlank() ? "" : " " + c.input()));
        settings.size(size);
        settings.material(material);
        tool.both(settings);
        c.print("kawe.brush.brush.equipped", name);
        ToolCommands.unbindHint(c);
    }

    static String pattern(CmdContext c) throws Exception {
        c.get("pattern", Types.PATTERN);
        return c.raw("pattern");
    }

    private static void sphere(CmdContext c) throws Exception {
        String pattern = pattern(c);
        double radius = radius(c, "radius");
        boolean hollow = c.flag('h');
        Brush brush = c.flag('f') ? BrushCommands::fallingSphere : u -> Shapes.sphere(u.edit(), u.target(), u.pattern(), u.size(), u.size(), u.size(), !hollow);
        equip(c, "sphere", brush, "kawe.brush.sphere", radius, pattern);
    }

    private static void fallingSphere(BrushUse u) {
        Edit edit = u.edit();
        BlockVec at = u.target();
        int radius = (int) Math.round(u.size());
        long radiusSq = Math.round(u.size() * u.size());
        Mask ground = (x, y, z) -> Masks.blocksMovement(edit.get(x, y, z));
        for (int z = -radius; z <= radius; z++) {
            long remaining = radiusSq - (long) z * z;
            if (remaining < 0) {
                continue;
            }
            int xRadius = (int) Math.sqrt(remaining);
            for (int x = -xRadius; x <= xRadius; x++) {
                long remainingY = remaining - (long) x * x;
                if (remainingY < 0) {
                    continue;
                }
                int yRadius = (int) Math.sqrt(remainingY);
                int ax = at.x() + x;
                int az = at.z() + z;
                int startY = Math.max(edit.minY(), at.y() - yRadius);
                int endY = Math.min(edit.maxY(), at.y() + yRadius);
                int surface = RegionOps.highest(edit, ax, az, edit.minY(), endY, ground);
                if (surface < startY - 1) {
                    int drop = startY - 1 - surface;
                    startY -= drop;
                    endY -= drop;
                }
                for (int y = startY; y <= endY; y++) {
                    u.pattern().apply(edit, ax, y, az);
                }
            }
        }
    }

    private static void cylinder(CmdContext c) throws Exception {
        String pattern = pattern(c);
        double radius = radius(c, "radius");
        int height = c.get("height", Types.INT);
        if (height == Integer.MIN_VALUE) {
            throw new CommandError(Component.text("Height is out of range"));
        }
        double thickness = c.get("thickness", Types.DOUBLE);
        if (thickness < 0) {
            throw new CommandError(Component.text("Thickness must be >= 0"));
        }
        boolean hollow = c.flag('h');
        equip(c, "cylinder", u -> Shapes.cylinder(u.edit(), u.target(), u.pattern(), u.size(), u.size(), height, thickness, !hollow),
                "kawe.brush.cylinder", radius, pattern);
    }

    private static void set(CmdContext c) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = radius(c, "radius");
        String pattern = pattern(c);
        equip(c, "set", u -> Ops.setBlocks(u.edit(), shape.at(u.target(), u.size()), u.pattern()), "kawe.brush.set", radius, pattern);
    }

    private static void smooth(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        int iterations = c.get("iterations", Types.INT);
        if (iterations < 0) {
            throw new CommandError(Component.text("Iterations must be >= 0"));
        }
        String mask = c.raw("mask");
        if (mask != null) {
            c.get("mask", Types.MASK);
        }
        equip(c, "smooth", u -> RegionOps.smooth(u.edit(), cube(u), iterations,
                mask == null ? null : Masks.parse(mask, u.context().parseContext(u.edit()))), "kawe.brush.smooth", radius, null);
    }

    static CuboidRegion cube(BrushUse u) {
        int size = (int) u.size();
        BlockVec at = u.target();
        return UtilityOps.clampY(u.edit(), at.subtract(size, size, size), at.add(size, size, size));
    }

    private static void snowSmooth(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        int iterations = c.get("iterations", Types.INT);
        Integer layers = c.flagValue('l', Types.INT);
        if (iterations < 0 || (layers != null && layers < 0)) {
            throw new CommandError(Component.text("Iterations and snow layers must be >= 0"));
        }
        String mask = c.flagRaw('m');
        if (mask != null) {
            c.flagValue('m', Types.MASK);
        }
        int snow = layers == null ? 1 : layers;
        equip(c, "snowsmooth", u -> RegionOps.snowSmooth(u.edit(), cube(u), iterations, snow,
                mask == null ? null : Masks.parse(mask, u.context().parseContext(u.edit()))), "kawe.brush.snowsmooth", radius, null);
    }

    private static void gravity(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        boolean fullHeight = c.flag('h');
        equip(c, "gravity", u -> {
            CuboidRegion area = cube(u);
            if (area != null) {
                RegionOps.fall(u.edit(), area, fullHeight, Pattern.of(Blocks.AIR.defaultBlockState()));
            }
        }, "kawe.brush.gravity", radius, null);
    }

    private static void butcher(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
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
            c.errorText(Component.text("No permission for -" + EntityFilters.letters(flags & ~allowed) + "; those mobs will be left alone"));
        }
        equip(c, "butcher", u -> u.afterCommit(result -> {
            BlockVec at = u.target();
            CylinderRegion area = new CylinderRegion(at, u.size(), u.size(), (int) Math.max(u.edit().minY(), at.y() - u.size()),
                    (int) Math.min(u.edit().maxY(), at.y() + u.size()));
            List<EntityRecord> removed = Entities.remove(u.edit().world(), area, u.edit().allowing(entity -> EntityFilters.butcherable(entity.getBukkitEntity(), allowed)));
            if (result.history() != null) {
                result.history().entitiesRemoved(removed);
            }
        }), "kawe.brush.butcher", radius, null);
    }

    private static void extinguish(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        equip(c, "extinguish", u -> {
            Edit edit = u.edit();
            Ops.replaceBlocks(edit, new EllipsoidRegion(u.target(), new Vec3(u.size(), u.size(), u.size())),
                    (x, y, z) -> edit.get(x, y, z).is(BlockTags.FIRE), Pattern.of(Blocks.AIR.defaultBlockState()));
        }, "kawe.brush.ex", radius, null);
    }

    private static void clipboard(CmdContext c) throws Exception {
        ClipboardHolder holder = c.clipboard();
        Clipboard sample = holder.clipboard();
        BlockVec dimensions = sample.dimensions();
        double size = Math.max(dimensions.x(), Math.max(dimensions.y(), dimensions.z())) / 2.0;
        BrushTool.checkBrushRadius(size);
        boolean air = !c.flag('a');
        boolean origin = c.flag('o');
        boolean entities = c.flag('e');
        boolean biomes = c.flag('b');
        boolean random = c.flag('r');
        String maskInput = c.flagRaw('m');
        if (maskInput != null) {
            Masks.parse(maskInput, c.parseContext(sample));
        }
        equip(c, "clipboard", u -> {
            ClipboardHolder current = u.context().clipboard();
            Clipboard clipboard = current.clipboard();
            Transform transform = current.transform();
            if (random) {
                transform = Transform.IDENTITY.rotateY(ThreadLocalRandom.current().nextInt(4) * 90).combine(transform);
            }
            BlockVec to = u.target();
            if (!origin) {
                Vec3 center = clipboard.region().center().subtract(clipboard.origin().toVec3());
                to = to.subtract(transform.apply(center).toBlock());
            }
            Mask source = maskInput == null ? null : Masks.parse(maskInput, u.context().parseContext(clipboard));
            ClipboardOps.paste(u.edit(), transform, clipboard, to, air, biomes, source);
            if (entities && !clipboard.entities().isEmpty()) {
                Transform used = transform;
                BlockVec placed = to;
                u.afterCommit(result -> {
                    List<EntityRecord> added = ClipboardOps.pasteEntities(u.edit(), used, clipboard, placed);
                    if (result.history() != null) {
                        result.history().entitiesAdded(added);
                    }
                });
            }
        }, "kawe.brush.clipboard", size, null);
    }

    private static void deform(CmdContext c) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = c.get("radius", Types.DOUBLE);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        BrushTool.checkBrushRadius(radius);
        String source = c.get("expression", Types.TEXT);
        compile(source);
        boolean raw = c.flag('r');
        boolean placement = c.flag('o');
        equip(c, "deform", u -> deformAt(u, shape, source, raw, placement), "kawe.brush.deform", radius, null);
    }

    private static Expression compile(String source) throws CommandError {
        try {
            return Expression.compile(source, "x", "y", "z");
        } catch (ExpressionException e) {
            throw new CommandError(Component.text(e.getMessage()));
        }
    }

    private static void deformAt(BrushUse u, Shape shape, String source, boolean raw, boolean placement) throws Exception {
        Vec3 zero = raw ? Vec3.ZERO : (placement ? u.context().placement() : u.target()).toVec3();
        Shapes.Generated result = Shapes.deform(u.edit(), shape.at(u.target(), u.size()), compile(source), zero, Vec3.ONE, u.context().session().timeout());
        if (result.timedOut() > 0) {
            u.context().errorText(Component.text(result.timedOut() + " blocks took too long to evaluate and were left unchanged (increase with //timeout)."));
        }
    }

    private static void shift(CmdContext c, String expression, String name) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = radius(c, "radius");
        equip(c, name, u -> deformAt(u, shape, expression, false, false), "kawe.brush." + name, radius, null);
    }

    private static void forest(CmdContext c) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = radius(c, "radius");
        double density = c.get("density", Types.DOUBLE);
        if (density < 0 || density > 100) {
            throw new CommandError(Component.text("Density must be in [0, 100]"));
        }
        Trees.Kind kind = c.get("type", RegionCommands.TREE);
        equip(c, "forest", u -> RegionOps.forest(u.edit(), shape.at(u.target(), u.size()), density / 100, kind), "kawe.brush.forest", radius, null);
    }

    private static void snow(CmdContext c) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = c.get("radius", Types.DOUBLE);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        BrushTool.checkBrushRadius(radius);
        boolean stack = c.flag('s');
        equip(c, "snow", u -> UtilityOps.snow(u.edit(), shape.at(u.target(), u.size()), stack), "kawe.brush.snow", radius, null);
    }

    private static void biome(CmdContext c) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = c.get("radius", Types.DOUBLE);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        BrushTool.checkBrushRadius(radius);
        Holder<Biome> biome = c.get("biome", BiomeCommands.BIOME);
        boolean column = c.flag('c');
        equip(c, "biome", u -> {
            Edit edit = u.edit();
            Region region = shape.at(u.target(), u.size());
            if (column) {
                BlockVec min = region.min();
                BlockVec max = region.max();
                Region full = region;
                Ops.parallelChunks(edit, new CuboidRegion(new BlockVec(min.x(), edit.minY(), min.z()), new BlockVec(max.x(), edit.maxY(), max.z())),
                        key -> {
                            for (int x = Math.max(min.x(), ChunkKey.x(key) << 4); x <= Math.min(max.x(), (ChunkKey.x(key) << 4) + 15); x++) {
                                for (int z = Math.max(min.z(), ChunkKey.z(key) << 4); z <= Math.min(max.z(), (ChunkKey.z(key) << 4) + 15); z++) {
                                    if (!Ops.flatContains(full, x, z)) {
                                        continue;
                                    }
                                    for (int y = edit.minY(); y <= edit.maxY(); y += 4) {
                                        edit.setBiome(x, y, z, biome);
                                    }
                                }
                            }
                        });
                return;
            }
            Ops.parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key),
                    (x, y, z) -> edit.setBiome(x, y, z, biome)));
        }, "kawe.brush.biome", radius, null);
    }

    private static void features(CmdContext c, boolean structure) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = c.get("radius", Types.DOUBLE);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        BrushTool.checkBrushRadius(radius);
        double density = c.get("density", Types.DOUBLE);
        if (density < 0 || density > 100) {
            throw new CommandError(Component.text("Density must be in [0, 100]"));
        }
        Identifier id = c.get("type", structure ? GenerationCommands.STRUCTURE : GenerationCommands.FEATURE);
        equip(c, structure ? "structure" : "feature", u -> {
            Edit edit = u.edit();
            Region region = shape.at(u.target(), u.size());
            BlockVec min = region.min();
            BlockVec max = region.max();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int x = min.x(); x <= max.x(); x++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    if (!Ops.flatContains(region, x, z) || random.nextDouble() * 100 >= density) {
                        continue;
                    }
                    for (int y = Math.min(edit.maxY(), max.y()); y >= Math.max(edit.minY(), min.y()); y--) {
                        BlockState state = edit.get(x, y, z);
                        if (state.isAir()) {
                            continue;
                        }
                        BlockVec at = new BlockVec(x, y + 1, z);
                        if (structure) {
                            Generation.structure(edit, id, at);
                        } else {
                            Generation.feature(edit, id, at);
                        }
                        break;
                    }
                }
            }
        }, "kawe.brush.feature", radius, null);
    }

    private static void catenary(CmdContext c) throws Exception {
        String pattern = pattern(c);
        double slack = c.get("lengthFactor", Types.DOUBLE);
        if (slack < 1) {
            throw InputParseException.of("kawe.error.outside-range-lower", "lengthFactor", "1");
        }
        equip(c, "catenary", new Brushes.Catenary(c.flag('h'), c.flag('s'), c.flag('d'), slack), "kawe.brush.spline", radius(c, "radius"), pattern);
    }

    private static void surfaceSpline(CmdContext c) throws Exception {
        String pattern = pattern(c);
        double quality = c.get("quality", Types.DOUBLE);
        if (quality <= 0) {
            throw new CommandError(Component.text("Quality must be > 0"));
        }
        equip(c, "surfacespline", new Brushes.SurfaceSpline(c.get("tension", Types.DOUBLE), c.get("bias", Types.DOUBLE), c.get("continuity", Types.DOUBLE),
                quality), "kawe.brush.surfacespline", radius(c, "radius"), pattern);
    }

    private static int count(CmdContext c, String name) throws Exception {
        double value = c.get(name, Types.DOUBLE);
        if (value < 0 || value > 100_000) {
            throw InputParseException.of("kawe.error.outside-range", name, "0", "100000");
        }
        return (int) value;
    }

    private static void splatter(CmdContext c) throws Exception {
        String pattern = pattern(c);
        equip(c, "splatter", Brushes.splatter(count(c, "points"), count(c, "recursion"), c.get("solid", Types.BOOL)), "kawe.brush.splatter",
                radius(c, "radius"), pattern);
    }

    private static void scatter(CmdContext c) throws Exception {
        String pattern = pattern(c);
        equip(c, "scatter", Brushes.scatter(count(c, "points"), count(c, "distance"), c.flag('o')), "kawe.brush.scatter", radius(c, "radius"), pattern);
    }

    private static void layer(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        List<String> layers = List.of(c.get("patterns", Types.TEXT).trim().split("\\s+"));
        for (String layer : layers) {
            Types.PATTERN.parse(layer, c);
        }
        equip(c, "layer", Brushes.layers(layers), "kawe.brush.layer", radius, null);
    }

    private static void shatter(CmdContext c) throws Exception {
        String pattern = pattern(c);
        int count = c.get("count", Types.INT);
        if (count < 1) {
            throw new CommandError(Component.text("Count must be >= 1"));
        }
        equip(c, "shatter", Brushes.shatter(count), "kawe.brush.shatter", radius(c, "radius"), pattern);
    }

    private static void populate(CmdContext c) throws Exception {
        String name = c.get("clipboard", Types.WORD);
        List<Clipboard> clipboards = Schematics.loadAll(name, Settings.get().loadAllFiles());
        if (clipboards.isEmpty()) {
            throw new CommandError("kawe.error.schematic.not.found", name);
        }
        String mask = c.raw("mask");
        if (mask != null) {
            c.get("mask", Types.MASK);
        }
        double density = c.get("density", Types.DOUBLE);
        if (density < 0 || density > 100) {
            throw new CommandError(Component.text("Density must be in [0, 100]"));
        }
        equip(c, "populateschematic", Brushes.populate(clipboards, mask, (int) Math.round(density), c.flag('r')), "kawe.brush.populateschematic",
                radius(c, "radius"), null);
    }

    private static void blendBall(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        int minFreqDiff = c.get("minFreqDiff", Types.INT);
        String mask = c.flagRaw('m');
        if (mask != null) {
            c.flagValue('m', Types.MASK);
        }
        equip(c, "blendball", TerrainBrushes.blendBall(minFreqDiff, c.flag('a'), mask), "kawe.brush.blendball", radius, null);
    }

    private static void morph(CmdContext c, String name, String permission, String erodeFaces, String erodeIterations, String fillFaces, String fillIterations)
            throws Exception {
        double radius = radius(c, "radius");
        int[] values = {c.get(erodeFaces, Types.INT), c.get(erodeIterations, Types.INT), c.get(fillFaces, Types.INT), c.get(fillIterations, Types.INT)};
        for (int value : values) {
            if (value < 0) {
                throw new CommandError(Component.text("Face counts and iterations must be >= 0"));
            }
        }
        equip(c, name, TerrainBrushes.morph(values[0], values[1], values[2], values[3]), permission, radius, null);
    }

    private static TerrainBrushes.HeightSource heightSource(CmdContext c, TerrainBrushes.HeightSource fallback) throws Exception {
        String image = c.raw("image");
        if (image == null || image.isBlank() || image.equalsIgnoreCase("none")) {
            return fallback;
        }
        if (image.equalsIgnoreCase("#clipboard")) {
            return TerrainBrushes.clipboard(c.clipboard().clipboard(), c.world().maxY() - c.world().minY() + 1);
        }
        return TerrainBrushes.image(HeightImages.load(image));
    }

    private static int rotation(CmdContext c) throws Exception {
        int rotation = c.get("rotation", Types.INT);
        if (rotation < 0 || rotation > 360) {
            throw InputParseException.of("kawe.error.outside-range", "rotation", "0", "360");
        }
        return rotation;
    }

    private static void terrain(CmdContext c, String name, boolean towards, TerrainBrushes.HeightSource shape) throws Exception {
        double radius = radius(c, "radius");
        double yscale = c.get("yscale", Types.DOUBLE);
        TerrainBrushes.HeightSource source = heightSource(c, shape);
        equip(c, name, TerrainBrushes.height(source, rotation(c), yscale, !c.flag('s'), towards, c.flag('r'), c.flag('l')), "kawe.brush.height", radius, null);
    }

    private static void stencil(CmdContext c) throws Exception {
        String pattern = pattern(c);
        double radius = radius(c, "radius");
        TerrainBrushes.HeightSource source = heightSource(c, TerrainBrushes.cone());
        equip(c, "stencil", TerrainBrushes.stencil(source, rotation(c), c.get("yscale", Types.DOUBLE), c.flag('w'), c.flag('r')), "kawe.brush.stencil",
                radius, pattern);
    }

    static void runAt(CmdContext c, BlockVec at, int radius, String commands, boolean print) throws Exception {
        String replaced = commands.replace("{x}", Integer.toString(at.x())).replace("{y}", Integer.toString(at.y()))
                .replace("{z}", Integer.toString(at.z())).replace("{world}", c.world().bukkit().getName()).replace("{size}", Integer.toString(radius));
        c.session().selector(c.world().bukkit(), new CuboidSelector(at.subtract(radius, radius, radius), at.add(radius, radius, radius)));
        Location location = new Location(c.world().bukkit(), at.x() + 0.5, at.y(), at.z() + 0.5, c.actor().yaw(), c.actor().pitch());
        Actor placed = c.actor().relocated(location, !print);
        for (String part : replaced.split(";")) {
            String line = part.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("/")) {
                line = line.substring(1);
            }
            Commands.dispatchInline(c, placed, line);
        }
    }

    private static void commandBrush(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        String commands = c.get("commands", Types.TEXT);
        boolean print = !c.flag('h');
        equip(c, "command", u -> runAt(u.context(), u.target(), (int) u.size(), commands, print), "kawe.brush.command", radius, null);
    }

    private static void scatterCommand(CmdContext c) throws Exception {
        double radius = radius(c, "radius");
        int points = count(c, "points");
        int distance = count(c, "distance");
        String commands = c.get("commands", Types.TEXT);
        boolean print = c.flag('p');
        equip(c, "scattercommand", u -> {
            for (BlockVec at : Brushes.scatterPoints(u.edit(), u.target(), u.size(), points, distance)) {
                runAt(u.context(), at, distance, commands, print);
            }
        }, "kawe.brush.scattercommand", radius, null);
    }

    private static Path brushFolder() {
        return Sched.plugin().getDataFolder().toPath().resolve("brushes").toAbsolutePath().normalize();
    }

    private static Path brushFile(Path folder, String name) throws CommandError {
        String clean = Path.of(name).getFileName().toString();
        Path file = folder.resolve(clean.endsWith(".brush") ? clean : clean + ".brush").normalize();
        if (!file.startsWith(folder)) {
            throw new CommandError("kawe.error.file-resolution.outside-root");
        }
        return file;
    }

    private static void save(CmdContext c) throws Exception {
        BrushTool tool = ToolCommands.brushTool(c, false);
        BrushSettings right = tool.right();
        if (right.brush() == null) {
            throw new CommandError("kawe.brush.brush.none");
        }
        String name = c.get("name", Types.WORD);
        boolean global = c.flag('g') && c.actor().has("kawe.brush.save.other");
        Path folder = global ? brushFolder() : brushFolder().resolve(c.actor().uuid().toString());
        Path file = brushFile(folder, name);
        Properties properties = new Properties();
        properties.setProperty("line", right.line());
        properties.setProperty("size", Double.toString(right.size()));
        optional(properties, "material", right.material());
        optional(properties, "mask", right.mask());
        optional(properties, "sourcemask", right.sourceMask());
        optional(properties, "tracemask", tool.traceMask());
        properties.setProperty("range", Integer.toString(tool.rangeSet() ? tool.range() : -1));
        properties.setProperty("target", tool.targetMode().name());
        properties.setProperty("offset", Integer.toString(tool.targetOffset()));
        properties.setProperty("scroll", right.scroll().name());
        properties.setProperty("scrollvalues", String.join(" ", right.scrollValues()));
        Files.createDirectories(folder);
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(out, null);
        }
        c.print("kawe.schematic.schematic.saved", file.getFileName().toString());
    }

    private static void optional(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static void load(CmdContext c) throws Exception {
        String name = c.get("name", Types.WORD);
        Path file = brushFile(brushFolder().resolve(c.actor().uuid().toString()), name);
        if (!Files.isRegularFile(file)) {
            file = brushFile(brushFolder(), name);
        }
        if (!Files.isRegularFile(file)) {
            throw new CommandError("kawe.error.brush.not.found", name);
        }
        Properties properties = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(in);
        }
        String line = properties.getProperty("line");
        if (line == null || !line.startsWith("brush ")) {
            throw new CommandError("kawe.error.brush.incompatible");
        }
        Commands.dispatchInline(c, line);
        BrushTool tool = ToolCommands.brushTool(c, false);
        BrushSettings settings = tool.right();
        settings.size(Double.parseDouble(properties.getProperty("size", Double.toString(settings.size()))));
        if (properties.containsKey("material")) {
            settings.material(properties.getProperty("material"));
        }
        settings.mask(properties.getProperty("mask"));
        settings.sourceMask(properties.getProperty("sourcemask"));
        tool.traceMask(properties.getProperty("tracemask"));
        tool.range(Integer.parseInt(properties.getProperty("range", "-1")));
        tool.targetMode(BrushTool.TargetMode.valueOf(properties.getProperty("target", BrushTool.TargetMode.TARGET_BLOCK_RANGE.name())));
        tool.targetOffset(Integer.parseInt(properties.getProperty("offset", "0")));
        String values = properties.getProperty("scrollvalues", "").strip();
        settings.scroll(BrushSettings.Scroll.valueOf(properties.getProperty("scroll", "NONE")), values.isEmpty() ? List.of() : List.of(values.split("\\s+")));
    }

    private static void list(CmdContext c) throws Exception {
        List<Component> lines = new ArrayList<>();
        Path own = c.actor().uuid() == null ? null : brushFolder().resolve(c.actor().uuid().toString());
        for (Path folder : own == null ? List.of(brushFolder()) : List.of(own, brushFolder())) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(p -> p.getFileName().toString().endsWith(".brush")).sorted().forEach(p -> {
                    String name = p.getFileName().toString();
                    String bare = name.substring(0, name.length() - ".brush".length());
                    lines.add(Component.text(" - " + bare + (folder.equals(own) ? "" : " (global)"))
                            .clickEvent(ClickEvent.suggestCommand("/brush loadbrush " + bare)));
                });
            }
        }
        if (lines.isEmpty()) {
            c.print("kawe.schematic.schematic.none");
            return;
        }
        Integer page = c.flagValue('p', Types.INT);
        try {
            c.print(new PaginationBox("Brushes", "/brush listbrush -p %page%", lines).forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    private static final ArgType<String> APPLY_TYPE = new ArgType<>() {
        @Override
        public String parse(String raw, CmdContext context) throws InputParseException {
            String type = raw.toLowerCase(Locale.ROOT);
            if (!type.equals("forest") && !type.equals("set") && !type.equals("item")) {
                throw new InputParseException(Component.text("Unknown type: " + raw + " (forest, item, set)"));
            }
            return type;
        }

        @Override
        public boolean accepts(String raw) {
            return raw.equalsIgnoreCase("forest") || raw.equalsIgnoreCase("set") || raw.equalsIgnoreCase("item");
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Stream.of("forest", "item", "set"), partial);
        }
    };

    private static void applyBrush(CmdContext c, boolean paint) throws Exception {
        Shape shape = c.get("shape", SHAPE);
        double radius = c.get("radius", Types.DOUBLE);
        if (radius < 0) {
            throw new CommandError(Component.text("Radius must be >= 0"));
        }
        BrushTool.checkBrushRadius(radius);
        double density = paint ? c.get("density", Types.DOUBLE) / 100 : 1;
        if (density < 0 || density > 1) {
            throw new CommandError(Component.text("Density must be in [0, 100]"));
        }
        String type = c.get("type", APPLY_TYPE);
        String value = c.raw("value");
        String name = paint ? "paint" : "apply";
        String permission = paint ? "kawe.brush.paint" : "kawe.brush.apply";
        if (type.equals("set")) {
            if (value == null || value.isBlank()) {
                throw new CommandError(Component.text("Usage: //brush " + name + " <shape> [radius] set <pattern>"));
            }
            Types.PATTERN.parse(value, c);
            equip(c, name, u -> {
                for (BlockVec at : spots(u, shape, paint, density, false)) {
                    u.pattern().apply(u.edit(), at.x(), at.y(), at.z());
                }
            }, permission, radius, value);
            return;
        }
        if (type.equals("item")) {
            if (!c.actor().has("kawe.brush.item")) {
                throw new CommandError("kawe.error.no-perm", "kawe.brush.item");
            }
            if (value == null || value.isBlank()) {
                throw new CommandError(Component.text("Usage: //brush " + name + " <shape> [radius]" + (paint ? " [density]" : "") + " item <item> [direction]"));
            }
            String itemText = value.strip();
            Direction face = Direction.UP;
            int space = itemText.lastIndexOf(' ');
            if (space > 0) {
                try {
                    face = ItemUse.face(Types.direction(itemText.substring(space + 1), c.actor()));
                    itemText = itemText.substring(0, space).strip();
                } catch (InputParseException ignored) {
                    face = Direction.UP;
                }
            }
            ItemStack stack = ItemUse.parse(itemText);
            Direction direction = face;
            c.print(Component.text("WARNING: ").append(Msg.tr(paint ? "kawe.brush.paint.item.warning" : "kawe.brush.apply.item.warning")));
            equip(c, name, u -> {
                Mask mask = u.edit().mask();
                List<BlockVec> spots = new ArrayList<>();
                for (BlockVec at : spots(u, shape, paint, density, false)) {
                    if (mask == null || mask.test(at.x(), at.y(), at.z())) {
                        spots.add(at);
                    }
                }
                ItemUse.Result result = ItemUse.use(u.edit().world(), spots, stack, direction);
                if (result.failed() > 0) {
                    u.context().error("kawe.brush.item-failures", result.failed());
                }
            }, "kawe.brush.item", radius, null);
            return;
        }
        Trees.Kind kind = RegionCommands.TREE.parse(value == null || value.isBlank() ? "tree" : value.strip(), c);
        equip(c, name, u -> {
            Map<BlockVec, Trees.Kind> requests = new HashMap<>();
            for (BlockVec at : spots(u, shape, paint, density, true)) {
                requests.put(at, kind);
            }
            Trees.plant(u.edit(), requests);
        }, permission, radius, null);
    }

    private static List<BlockVec> spots(BrushUse u, Shape shape, boolean paint, double density, boolean above) {
        Edit edit = u.edit();
        Region region = shape.at(u.target(), u.size());
        List<BlockVec> out = new ArrayList<>();
        BlockVec min = region.min();
        BlockVec max = region.max();
        if (!paint) {
            region.forEach((x, y, z) -> {
                if (y >= edit.minY() && y <= edit.maxY()) {
                    out.add(new BlockVec(x, y, z));
                }
            });
            return out;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int bottom = Math.max(edit.minY(), min.y());
        int top = Math.min(edit.maxY(), max.y());
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                if (!Ops.flatContains(region, x, z) || random.nextDouble() >= density) {
                    continue;
                }
                for (int y = top; y >= bottom; y--) {
                    if (!edit.get(x, y, z).isAir()) {
                        out.add(new BlockVec(x, above ? y + 1 : y, z));
                        break;
                    }
                }
            }
        }
        return out;
    }

    private static void imageBrush(CmdContext c) throws Exception {
        BufferedImage image = HeightImages.load(c.get("image", Types.WORD));
        double radius = radius(c, "radius");
        equip(c, "image", TerrainBrushes.image(image, c.flag('a')), "kawe.brush.image", radius, null);
    }

    private static void rock(CmdContext c) throws Exception {
        String pattern = pattern(c);
        List<Double> radii = c.get("radius", Types.RADII);
        if (radii.size() != 1 && radii.size() != 3) {
            throw new CommandError(Component.text("Radius takes one value or x,y,z"));
        }
        double rx = radii.getFirst();
        double ry = radii.get(radii.size() == 1 ? 0 : 1);
        double rz = radii.get(radii.size() == 1 ? 0 : 2);
        if (rx <= 0 || ry <= 0 || rz <= 0) {
            throw new CommandError(Component.text("Radius must be > 0"));
        }
        double max = Math.max(rx, Math.max(ry, rz));
        BrushTool.checkBrushRadius(max);
        double roundness = c.get("roundness", Types.DOUBLE);
        if (roundness < 0 || roundness > 100) {
            throw InputParseException.of("kawe.error.outside-range", "roundness", "0", "100");
        }
        double frequency = c.get("frequency", Types.DOUBLE) / 100;
        double amplitude = c.get("amplitude", Types.DOUBLE) / 100;
        Vec3 shape = new Vec3(rx / max, ry / max, rz / max);
        equip(c, "rock", u -> Generation.blob(u.edit(), u.target(), u.pattern(), u.size(), frequency, amplitude, shape, roundness / 100),
                "kawe.brush.rock", max, pattern);
    }
}
