package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import teacommontea.kawe.color.BlockColors;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionException;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.op.Caves;
import teacommontea.kawe.op.Generation;
import teacommontea.kawe.op.Ops;
import teacommontea.kawe.op.RegionOps;
import teacommontea.kawe.op.Shapes;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.tool.HeightImages;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class GenerationCommands {

    record Origin(Vec3 zero, Vec3 unit) {
    }

    private GenerationCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("/hcyl").perm("kawe.generation.cylinder").desc("Generates a hollow cylinder.")
                .arg("pattern", Types.PATTERN).arg("radii", Types.RADII).opt("height", Types.INT, "1").opt("thickness", Types.DOUBLE, "0")
                .run(GenerationCommands::hcyl));
        Commands.add(Cmd.of("/cyl").perm("kawe.generation.cylinder").desc("Generates a cylinder.")
                .arg("pattern", Types.PATTERN).arg("radii", Types.RADII).opt("height", Types.INT, "1").flag('h', "Make a hollow cylinder")
                .run(GenerationCommands::cyl));
        Commands.add(Cmd.of("/cone").perm("kawe.generation.cone").desc("Generates a cone.")
                .arg("pattern", Types.PATTERN).arg("radii", Types.RADII).opt("height", Types.INT, "1").opt("thickness", Types.DOUBLE, "1")
                .flag('h', "Make a hollow cone").run(GenerationCommands::cone));
        Commands.add(Cmd.of("/sphere").perm("kawe.generation.sphere").desc("Generates a filled sphere.")
                .arg("pattern", Types.PATTERN).arg("radii", Types.RADII).flag('r', "Raise the bottom of the sphere to the placement position")
                .flag('h', "Make a hollow sphere").run(c -> sphere(c, c.flag('h'))));
        Commands.add(Cmd.of("/hsphere").perm("kawe.generation.sphere").desc("Generates a hollow sphere.")
                .arg("pattern", Types.PATTERN).arg("radii", Types.RADII).flag('r', "Raise the bottom of the sphere to the placement position")
                .run(c -> sphere(c, true)));
        Commands.add(Cmd.of("/pyramid").confirm(Confirm.RADIUS, "size").perm("kawe.generation.pyramid").desc("Generate a filled pyramid")
                .arg("pattern", Types.PATTERN).arg("size", Types.INT).flag('h', "Make a hollow pyramid")
                .run(c -> pyramid(c, c.flag('h'))));
        Commands.add(Cmd.of("/hpyramid").confirm(Confirm.RADIUS, "size").perm("kawe.generation.pyramid").desc("Generate a hollow pyramid")
                .arg("pattern", Types.PATTERN).arg("size", Types.INT).run(c -> pyramid(c, true)));
        Commands.add(Cmd.of("/generate").aliases("/gen", "/g").confirm(Confirm.REGION).perm("kawe.generation.shape").desc("Generates a shape according to a formula.")
                .arg("pattern", Types.PATTERN).arg("expression", Types.TEXT).flag('h', "Generate a hollow shape")
                .flag('r', "Use the game's coordinate origin").flag('o', "Use the placement's coordinate origin")
                .flag('c', "Use the selection's center as origin").run(GenerationCommands::generate));
        Commands.add(Cmd.of("forestgen").aliases("/forestgen").perm("kawe.generation.forest").desc("Generate a forest")
                .opt("size", Types.INT, "10").opt("type", RegionCommands.TREE, "tree").opt("density", Types.DOUBLE, "5").run(GenerationCommands::forestGen));
        Commands.add(Cmd.of("pumpkins").aliases("/pumpkins").perm("kawe.generation.pumpkins").desc("Generate pumpkin patches")
                .opt("size", Types.INT, "10").opt("density", Types.DOUBLE, "0.02").run(GenerationCommands::pumpkins));
        Commands.add(Cmd.of("/feature").aliases("/placefeature").perm("kawe.generation.feature").desc("Generate Minecraft features")
                .arg("feature", FEATURE).run(GenerationCommands::feature));
        Commands.add(Cmd.of("/structure").perm("kawe.generation.structure").desc("Generate Minecraft structures")
                .arg("structure", STRUCTURE).run(GenerationCommands::structure));
        Commands.add(Cmd.of("/generatebiome").aliases("/genbiome", "/gb").confirm(Confirm.REGION).perm("kawe.generation.shape.biome")
                .desc("Sets biome according to a formula.").arg("biome", BiomeCommands.BIOME).arg("expression", Types.TEXT)
                .flag('h', "Generate a hollow shape").flag('r', "Use the game's coordinate origin").flag('o', "Use the placement's coordinate origin")
                .flag('c', "Use the selection's center as origin").run(GenerationCommands::generateBiome));
        Commands.add(Cmd.of("/caves").confirm(Confirm.REGION).perm("kawe.generation.caves").desc("Generates a cave network")
                .opt("size", Types.INT, "8").opt("frequency", Types.INT, "40").opt("rarity", Types.INT, "7").opt("minY", Types.INT, "8")
                .opt("maxY", Types.INT, "127").opt("systemFrequency", Types.INT, "1").opt("individualRarity", Types.INT, "25")
                .opt("pocketChance", Types.INT, "0").opt("pocketMin", Types.INT, "0").opt("pocketMax", Types.INT, "3").run(GenerationCommands::caves));
        Commands.add(Cmd.of("/ores").confirm(Confirm.REGION).perm("kawe.generation.ore").desc("Generates ores")
                .arg("mask", Types.MASK).flag('b', "Make all ores deepslate equivalent for y<0")
                .flag('d', "Make all ores deepslate equivalent when placed into deepslate, tuff, etc.").run(GenerationCommands::ores));
        Commands.add(Cmd.of("/ore").confirm(Confirm.REGION).perm("kawe.generation.ore").desc("Generates ores")
                .arg("mask", Types.MASK).arg("pattern", Types.PATTERN).arg("size", Types.INT).opt("freq", Types.INT, "10")
                .opt("rarity", Types.INT, "100").opt("minY", Types.INT, "0").opt("maxY", Types.INT, "63").run(GenerationCommands::ore));
        Commands.add(Cmd.of("/img").aliases("/image", "image").perm("kawe.generation.image").desc("Generate an image")
                .arg("image", Types.WORD).opt("randomize", Types.BOOL, "true").opt("threshold", Types.INT, "100").opt("dimensions", Types.WORD, "")
                .run(GenerationCommands::image));
        Commands.add(Cmd.of("/blob").aliases("/rock").perm("kawe.generation.blob").desc("Creates a distorted sphere")
                .arg("pattern", Types.PATTERN).opt("size", Types.DOUBLE, "5").opt("radius", Types.RADII, "5").opt("roundness", Types.DOUBLE, "100")
                .opt("frequency", Types.DOUBLE, "30").opt("amplitude", Types.DOUBLE, "50").run(GenerationCommands::blob));
    }

    static final ArgType<Identifier> FEATURE = registryKey("feature", () -> Generation.features(MinecraftServer.getServer().registryAccess()));
    static final ArgType<Identifier> STRUCTURE = registryKey("structure", () -> Generation.structures(MinecraftServer.getServer().registryAccess()));

    private static ArgType<Identifier> registryKey(String kind, Supplier<? extends Registry<?>> registry) {
        return new ArgType<>() {
            @Override
            public Identifier parse(String raw, CmdContext context) throws InputParseException {
                Identifier id = Identifier.tryParse(raw.toLowerCase(Locale.ROOT));
                if (id == null || !registry.get().containsKey(id)) {
                    throw new InputParseException(Component.text("Unknown " + kind + ": " + raw));
                }
                return id;
            }

            @Override
            public List<String> suggest(String partial, CmdContext context) {
                return Types.filter(registry.get().keySet().stream().map(Identifier::toString), partial);
            }
        };
    }

    private static double density(CmdContext c, double max) throws Exception {
        double density = c.get("density", Types.DOUBLE);
        if (density < 0 || density > max) {
            throw new CommandError(Component.text("Density must be between 0 and " + (max == 1 ? "1" : "100")));
        }
        return density;
    }

    private static void forestGen(CmdContext c) throws Exception {
        int size = c.get("size", Types.INT);
        if (size < 0) {
            throw new CommandError(Component.text("Size must be >= 0"));
        }
        c.checkRadius(size);
        double density = density(c, 100) / 100;
        Edit edit = c.edit();
        Region area = Generation.around(edit, c.placement(), size);
        long trees = area == null ? 0 : RegionOps.forest(edit, area, density, c.get("type", RegionCommands.TREE));
        c.print("kawe.forestgen.created", trees, c.commit(edit).changed());
    }

    private static void pumpkins(CmdContext c) throws Exception {
        int size = c.get("size", Types.INT);
        if (size < 0) {
            throw new CommandError(Component.text("Size must be >= 0"));
        }
        c.checkRadius(size);
        double density = density(c, 1);
        Edit edit = c.edit();
        long patches = Generation.pumpkins(edit, c.placement(), size, density);
        c.print("kawe.pumpkins.created", patches, c.commit(edit).changed());
    }

    private static void feature(CmdContext c) throws Exception {
        Edit edit = c.edit();
        c.requireActorWorld();
        int placed = Generation.feature(edit, c.get("feature", FEATURE), c.placement());
        placedReport(c, edit, placed, "kawe.feature.created", "kawe.generate.feature.failed");
    }

    private static void structure(CmdContext c) throws Exception {
        Edit edit = c.edit();
        c.requireActorWorld();
        int placed = Generation.structure(edit, c.get("structure", STRUCTURE), c.placement());
        placedReport(c, edit, placed, "kawe.structure.created", "kawe.generate.structure.failed");
    }

    private static void placedReport(CmdContext c, Edit edit, int placed, String created, String failed) throws Exception {
        if (placed == Generation.UNOWNED) {
            throw new CommandError(Component.text("It reaches into an area another server thread owns; move closer to its center or load the area first"));
        }
        if (placed <= 0) {
            c.error(failed);
            return;
        }
        long changed = c.commit(edit).changed();
        c.unstuck();
        c.print(created, changed);
    }

    private static void generateBiome(CmdContext c) throws Exception {
        Region region = c.selection();
        Origin origin = origin(c, region);
        Expression expression;
        try {
            expression = Expression.compile(c.get("expression", Types.TEXT), "x", "y", "z");
        } catch (ExpressionException e) {
            c.errorText(Component.text(e.getMessage()));
            return;
        }
        Edit edit = c.edit();
        Generation.BiomeShape result;
        try {
            result = Generation.biomeShape(edit, region, c.get("biome", BiomeCommands.BIOME), expression, origin.zero(), origin.unit(), c.flag('h'), c.session().timeout());
        } catch (ExpressionException e) {
            c.errorText(Component.text(e.getMessage()));
            return;
        }
        Edit.Result committed = c.commit(edit);
        if (result.timedOut() > 0) {
            c.errorText(Component.text(committed.biomes() + " biomes changed. " + result.timedOut()
                    + " biomes took too long to evaluate (increase time with //timeout)"));
            return;
        }
        c.print("kawe.generatebiome.changed", committed.biomes());
    }

    private static void caves(CmdContext c) throws Exception {
        Region region = c.selection();
        int size = c.get("size", Types.INT);
        if (size < 2 || size > 32) {
            throw InputParseException.of("kawe.error.outside-range", "size", "2", "32");
        }
        int frequency = c.get("frequency", Types.INT);
        int minY = c.get("minY", Types.INT);
        int maxY = c.get("maxY", Types.INT);
        int pocketMin = c.get("pocketMin", Types.INT);
        int pocketMax = c.get("pocketMax", Types.INT);
        if (frequency < 1) {
            throw InputParseException.of("kawe.error.outside-range-lower", "frequency", "1");
        }
        if (minY > maxY) {
            throw InputParseException.of("kawe.error.argument-size-mismatch", "minY", "maxY");
        }
        if (pocketMin < 0 || pocketMin > pocketMax) {
            throw InputParseException.of("kawe.error.argument-size-mismatch", "pocketMin", "pocketMax");
        }
        int systemFrequency = c.get("systemFrequency", Types.INT);
        if (systemFrequency < 0) {
            throw InputParseException.of("kawe.error.outside-range-lower", "systemFrequency", "0");
        }
        Edit edit = c.edit();
        Caves.carve(edit, region, new Caves.Settings(size, frequency, c.get("rarity", Types.INT), minY, maxY, systemFrequency,
                c.get("individualRarity", Types.INT), c.get("pocketChance", Types.INT), pocketMin, pocketMax));
        c.print("kawe.visitor.visitor.block", c.commit(edit).changed());
    }

    private static void ores(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Generation.ores(edit, region, c.get("mask", Types.MASK), c.flag('b'), c.flag('d'));
        c.print("kawe.visitor.visitor.block", c.commit(edit).changed());
    }

    private static void ore(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        int size = c.get("size", Types.INT);
        int frequency = c.get("freq", Types.INT);
        int rarity = c.get("rarity", Types.INT);
        int minY = c.get("minY", Types.INT);
        int maxY = c.get("maxY", Types.INT);
        if (size < 0 || frequency < 0) {
            throw InputParseException.of("kawe.error.outside-range-lower", size < 0 ? "size" : "freq", "0");
        }
        if (rarity < 0 || rarity > 100) {
            throw InputParseException.of("kawe.error.outside-range", "rarity", "0", "100");
        }
        if (minY < edit.minY()) {
            throw InputParseException.of("kawe.error.outside-range-lower", "miny", String.valueOf(edit.minY()));
        }
        if (maxY > edit.maxY()) {
            throw InputParseException.of("kawe.error.outside-range-upper", "maxy", String.valueOf(edit.maxY()));
        }
        if (minY >= maxY) {
            throw InputParseException.of("kawe.error.argument-size-mismatch", "miny", "maxy");
        }
        Generation.ore(edit, region, c.get("mask", Types.MASK), c.get("pattern", Types.PATTERN), size, frequency, rarity, minY, maxY, false);
        c.print("kawe.visitor.visitor.block", c.commit(edit).changed());
    }

    private static final long MAX_IMAGE_PIXELS = 8_294_400L;

    private static void image(CmdContext c) throws Exception {
        BufferedImage image = HeightImages.load(c.get("image", Types.WORD));
        String dimensions = c.raw("dimensions");
        if (dimensions != null && !dimensions.isBlank()) {
            String[] parts = dimensions.split("[x,]");
            if (parts.length != 2) {
                throw new CommandError(Component.text("Dimensions are width,height or widthxheight"));
            }
            int width;
            int height;
            try {
                width = Integer.parseInt(parts[0].trim());
                height = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw InputParseException.of("kawe.error.invalid-number.matches", dimensions);
            }
            if (width < 1 || height < 1 || (long) width * height > MAX_IMAGE_PIXELS) {
                throw new CommandError("kawe.error.image-dimensions", MAX_IMAGE_PIXELS);
            }
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, width, height, null);
            g.dispose();
            image = scaled;
        }
        if ((long) image.getWidth() * image.getHeight() > MAX_IMAGE_PIXELS) {
            throw new CommandError("kawe.error.image-dimensions", MAX_IMAGE_PIXELS);
        }
        int threshold = c.get("threshold", Types.INT);
        if (threshold < 0 || threshold > 100) {
            throw InputParseException.of("kawe.error.outside-range", "threshold", "0", "100");
        }
        BlockColors.Palette palette = c.session().palette().varianceRange(0, threshold);
        boolean randomize = c.get("randomize", Types.BOOL);
        BlockVec origin = c.placement();
        Edit edit = c.edit();
        if (origin.y() < edit.minY() || origin.y() > edit.maxY()) {
            throw new CommandError("kawe.cancel.reason.world.limit", origin.y());
        }
        BufferedImage pixels = image;
        CuboidRegion area = new CuboidRegion(origin, origin.add(pixels.getWidth() - 1, 0, pixels.getHeight() - 1));
        Ops.parallelChunks(edit, area, key -> area.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            int color = pixels.getRGB(x - origin.x(), z - origin.z());
            if (color >>> 24 == 0) {
                return;
            }
            Block block = randomize ? palette.dithered(color | 0xFF000000) : palette.nearest(color | 0xFF000000);
            if (block != null) {
                edit.set(x, y, z, block.defaultBlockState());
            }
        }));
        c.print("kawe.visitor.visitor.block", c.commit(edit).changed());
    }

    private static void blob(CmdContext c) throws Exception {
        double size = c.get("size", Types.DOUBLE);
        if (size <= 0) {
            throw new CommandError(Component.text("Size must be > 0"));
        }
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
        c.checkRadius(max);
        c.checkRadius(size * 2);
        double roundness = c.get("roundness", Types.DOUBLE);
        if (roundness < 0 || roundness > 100) {
            throw InputParseException.of("kawe.error.outside-range", "roundness", "0", "100");
        }
        Edit edit = c.edit();
        Generation.blob(edit, c.placement(), c.get("pattern", Types.PATTERN), size, c.get("frequency", Types.DOUBLE) / 100,
                c.get("amplitude", Types.DOUBLE) / 100, new Vec3(rx / max, ry / max, rz / max), roundness / 100);
        long changed = c.commit(edit).changed();
        c.unstuck();
        c.print("kawe.blob.created", changed);
    }

    private static double[] radii(CmdContext c, int max, String invalidKey, boolean error, double floor) throws Exception {
        List<Double> radii = c.get("radii", Types.RADII);
        if (radii.size() != 1 && radii.size() != max) {
            if (error) {
                c.error(invalidKey);
            } else {
                c.print(invalidKey);
            }
            return null;
        }
        double[] out = new double[max];
        for (int i = 0; i < max; i++) {
            double value = radii.get(radii.size() == 1 ? 0 : i);
            if (value < 0) {
                throw new CommandError(Component.text("Radius must be >= 0"));
            }
            out[i] = Math.max(floor, value);
            c.checkRadius(out[i]);
        }
        return out;
    }

    private static int height(CmdContext c) throws Exception {
        int height = c.get("height", Types.INT);
        if (height == Integer.MIN_VALUE) {
            throw new CommandError(Component.text("Height is out of range"));
        }
        c.checkRadius(Math.abs((double) height));
        return height;
    }

    private static double thickness(CmdContext c) throws Exception {
        double thickness = c.get("thickness", Types.DOUBLE);
        if (thickness < 0) {
            throw new CommandError(Component.text("Thickness must be >= 0"));
        }
        return thickness;
    }

    private static void cyl(CmdContext c) throws Exception {
        double[] radii = radii(c, 2, "kawe.cyl.invalid-radius", false, 1);
        if (radii == null) {
            return;
        }
        int height = height(c);
        BlockVec pos = c.placement();
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        Shapes.cylinder(edit, pos, pattern, radii[0], radii[1], height, 0, !c.flag('h'));
        Edit.Result result = c.commit(edit);
        c.unstuck();
        c.print("kawe.cyl.created", result.changed());
    }

    private static void hcyl(CmdContext c) throws Exception {
        double[] radii = radii(c, 2, "kawe.cyl.invalid-radius", false, 1);
        if (radii == null) {
            return;
        }
        int height = height(c);
        double thickness = thickness(c);
        if (thickness > radii[0] || thickness > radii[1]) {
            c.print("kawe.hcyl.thickness-too-large");
            return;
        }
        BlockVec pos = c.placement();
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        Shapes.cylinder(edit, pos, pattern, radii[0], radii[1], height, thickness, false);
        Edit.Result result = c.commit(edit);
        c.unstuck();
        c.print("kawe.cyl.created", result.changed());
    }

    private static void cone(CmdContext c) throws Exception {
        double[] radii = radii(c, 2, "kawe.cone.invalid-radius", true, 1);
        if (radii == null) {
            return;
        }
        int height = height(c);
        double thickness = thickness(c);
        if (c.flag('h') && thickness == 0) {
            throw new CommandError(Component.text("Thickness must be > 0 for a hollow cone"));
        }
        BlockVec pos = c.placement();
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        Shapes.cone(edit, pos, pattern, radii[0], radii[1], height, !c.flag('h'), thickness);
        Edit.Result result = c.commit(edit);
        c.unstuck();
        c.info("kawe.cone.created", result.changed());
    }

    private static void sphere(CmdContext c, boolean hollow) throws Exception {
        double[] radii = radii(c, 3, "kawe.sphere.invalid-radius", false, 0);
        if (radii == null) {
            return;
        }
        BlockVec pos = c.placement();
        if (c.flag('r')) {
            pos = pos.add(0, (int) Math.floor(radii[1] + 0.5), 0);
        }
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        Shapes.sphere(edit, pos, pattern, radii[0], radii[1], radii[2], !hollow);
        Edit.Result result = c.commit(edit);
        c.unstuck();
        c.print("kawe.sphere.created", result.changed());
    }

    private static void pyramid(CmdContext c, boolean hollow) throws Exception {
        int size = c.get("size", Types.INT);
        if (size < 1) {
            throw new CommandError(Component.text("Size must be >= 1"));
        }
        c.checkRadius(size);
        BlockVec pos = c.placement();
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        Shapes.pyramid(edit, pos, pattern, size, !hollow);
        Edit.Result result = c.commit(edit);
        c.unstuck();
        c.print("kawe.pyramid.created", result.changed());
    }

    static Origin origin(CmdContext c, Region region) throws Exception {
        int chosen = (c.flag('r') ? 1 : 0) + (c.flag('o') ? 1 : 0) + (c.flag('c') ? 1 : 0);
        if (chosen > 1) {
            throw new CommandError(Component.text("Use only one of -r, -o and -c"));
        }
        if (c.flag('r')) {
            return new Origin(Vec3.ZERO, Vec3.ONE);
        }
        if (c.flag('o')) {
            return new Origin(c.placement().toVec3(), Vec3.ONE);
        }
        Vec3 min = region.min().toVec3();
        Vec3 max = region.max().toVec3();
        Vec3 zero = max.add(min).multiply(0.5);
        if (c.flag('c')) {
            return new Origin(zero, Vec3.ONE);
        }
        Vec3 unit = max.subtract(zero);
        return new Origin(zero, new Vec3(unit.x() == 0 ? 1 : unit.x(), unit.y() == 0 ? 1 : unit.y(), unit.z() == 0 ? 1 : unit.z()));
    }

    private static void generate(CmdContext c) throws Exception {
        Region region = c.selection();
        Origin origin = origin(c, region);
        String source = c.get("expression", Types.TEXT);
        Expression expression;
        try {
            expression = Expression.compile(source, "x", "y", "z", "type", "data");
        } catch (ExpressionException e) {
            c.errorText(Component.text(e.getMessage()));
            return;
        }
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        Shapes.Generated result;
        try {
            result = Shapes.generate(edit, region, pattern, expression, origin.zero(), origin.unit(), c.flag('h'), c.session().timeout());
        } catch (ExpressionException e) {
            c.errorText(Component.text(e.getMessage()));
            return;
        }
        Edit.Result committed = c.commit(edit);
        c.unstuck();
        if (result.timedOut() > 0) {
            c.errorText(Component.text(committed.changed() + " blocks changed. " + result.timedOut()
                    + " blocks took too long to evaluate (increase with //timeout)."));
            return;
        }
        c.print("kawe.generate.created", committed.changed());
    }
}
