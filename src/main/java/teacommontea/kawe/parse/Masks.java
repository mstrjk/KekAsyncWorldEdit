package teacommontea.kawe.parse;

import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionException;
import teacommontea.kawe.expr.WorldExprEnvironment;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Simplex;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class Masks {

    @FunctionalInterface
    public interface Factory {
        Mask create(List<String> args, ParseContext context) throws InputParseException;
    }

    private static final Map<String, Factory> FACTORIES = new LinkedHashMap<>();

    static {
        register(c -> existing(c.extent()), "#existing");
        register(c -> air(c.extent()), "#air");
        register(c -> states(c.extent(), BlockMatcher.all(Masks::blocksMovement)), "#solid");
        register(c -> states(c.extent(), BlockMatcher.all(state -> state.getBlock() instanceof LiquidBlock)), "#liquid");
        register(c -> Mask.ALWAYS, "#true");
        register(c -> Mask.NEVER, "#false");
        register(Masks::region, "#region", "#selection", "#sel");
        register(Masks::region, "#dregion", "#dselection", "#dsel");
        register(Masks::surface, "#surface");
        register(Masks::wall, "#wall");
        register((args, c) -> negate(args, c), "!");
        register((args, c) -> offset(args, c, -1), ">");
        register((args, c) -> offset(args, c, 1), "<");
        register(Masks::noise, "%");
        register(Masks::biome, "$");
        register(Masks::adjacent, "~");
        register(Masks::beside, "#beside", "|");
        register(Masks::richOffset, "#offset");
        register(Masks::expression, "=");
        register(c -> axis(c, 0), "#xaxis");
        register(c -> axis(c, 1), "#yaxis");
        register(c -> axis(c, 2), "#zaxis");
        register(Masks::adjacent2d, "~2d", "adjacent2d");
        register((args, c) -> slope(args, c, Slope.ANGLE), "/", "#angle", "\\");
        register((args, c) -> slope(args, c, Slope.EXTREMA), "#extrema");
        register((args, c) -> slope(args, c, Slope.ROC), "#roc");
        register(Masks::radius, "{");
        register(Masks::simplex, "#simplex");
        register(Masks::surfaceAngle, "#surfaceangle");
        register(Masks::hotbar, "#hotbar");
        register((args, c) -> blockStates(args, c, false), "^");
        register((args, c) -> blockStates(args, c, true), "^=");
    }

    private Masks() {
    }

    public static void register(Factory factory, String... aliases) {
        for (String alias : aliases) {
            FACTORIES.put(alias, factory);
        }
    }

    private static void register(Simple factory, String... aliases) {
        register((args, c) -> factory.create(c), aliases);
    }

    @FunctionalInterface
    private interface Simple {
        Mask create(ParseContext context) throws InputParseException;
    }

    public static Set<String> aliases() {
        return FACTORIES.keySet();
    }

    public static Mask parse(String input, ParseContext context) throws InputParseException {
        if (input == null || input.isEmpty()) {
            throw InputParseException.of("kawe.error.invalid-arguments", "mask");
        }
        List<Input.Entry> entries = Input.split(input);
        List<List<Mask>> groups = new ArrayList<>();
        groups.add(new ArrayList<>());
        for (int e = 0; e < entries.size(); e++) {
            Input.Entry entry = entries.get(e);
            if (entry.and()) {
                groups.add(new ArrayList<>());
            }
            String command = entry.command();
            String full = entry.full();
            Mask mask = null;
            if (command.isEmpty()) {
                mask = parse(String.join(",", entry.args()), context);
            } else if (FACTORIES.containsKey(command.toLowerCase(Locale.ROOT))) {
                mask = FACTORIES.get(command.toLowerCase(Locale.ROOT)).create(entry.args(), context);
            } else {
                char first = command.charAt(0);
                boolean shorthand = full.length() > 1 && full.charAt(1) != '[';
                if (first == '#' && command.length() > 1 && command.charAt(1) != '#') {
                    throw InputParseException.of("kawe.error.parse.unknown-mask", full, "");
                }
                if (first == '=') {
                    StringBuilder rest = new StringBuilder(full.substring(1));
                    while (e + 1 < entries.size() && !entries.get(e + 1).and()) {
                        rest.append(',').append(entries.get(++e).full());
                    }
                    groups.getLast().add(FACTORIES.get("=").create(List.of(rest.toString()), context));
                    continue;
                }
                if (shorthand) {
                    switch (first) {
                        case '\\', '/', '{', '|', '~' -> {
                            String value = command.substring(1) + (entry.args().isEmpty() ? "" : "[" + String.join("][", entry.args()) + "]");
                            if (value.contains(":")) {
                                if (value.charAt(0) == ':') {
                                    value = value.substring(1);
                                }
                                value = value.replace(":", "][");
                            }
                            mask = parse(first + "[" + value + "]", context);
                        }
                        case '%', '$', '<', '>', '!' -> {
                            StringBuilder rest = new StringBuilder(full.substring(1));
                            while (e + 1 < entries.size() && !entries.get(e + 1).and()) {
                                rest.append(',').append(entries.get(++e).full());
                            }
                            mask = FACTORIES.get(String.valueOf(first)).create(List.of(rest.toString()), context);
                        }
                        case '#' -> {
                            if (command.startsWith("##")) {
                                mask = states(context.extent(), tagMatch(command.substring(2)));
                            }
                        }
                        default -> {
                        }
                    }
                }
                if (mask == null) {
                    if (command.startsWith("[") && Input.matchingBracket(command, 0) == command.length() - 1) {
                        mask = parse(command.substring(1, command.length() - 1), context);
                    } else {
                        mask = states(context.extent(), BlockMatcher.parse(full, context));
                    }
                }
            }
            groups.getLast().add(mask);
        }
        List<Mask> unions = new ArrayList<>();
        for (List<Mask> group : groups) {
            if (group.size() == 1) {
                unions.add(group.getFirst());
            } else if (!group.isEmpty()) {
                unions.add(anyOf(group));
            }
        }
        return unions.size() == 1 ? unions.getFirst() : allOf(unions);
    }

    private static BitSet tagMatch(String name) throws InputParseException {
        if (name.startsWith("*")) {
            BitSet out = new BitSet();
            String pattern = name.substring(1).toLowerCase(Locale.ROOT);
            for (var named : BuiltInRegistries.BLOCK.getTags().toList()) {
                Identifier location = named.key().location();
                if (location.getPath().contains(pattern) || location.toString().contains(pattern)) {
                    out.or(BlockMatcher.tag(location.toString()));
                }
            }
            if (out.isEmpty()) {
                throw InputParseException.of("kawe.error.unknown-tag", name);
            }
            return out;
        }
        return BlockMatcher.tag(name);
    }

    public static Mask anyOf(List<Mask> masks) {
        Mask[] array = masks.toArray(Mask[]::new);
        return (x, y, z) -> {
            for (Mask m : array) {
                if (m.test(x, y, z)) {
                    return true;
                }
            }
            return false;
        };
    }

    public static Mask allOf(List<Mask> masks) {
        Mask[] array = masks.toArray(Mask[]::new);
        return (x, y, z) -> {
            for (Mask m : array) {
                if (!m.test(x, y, z)) {
                    return false;
                }
            }
            return true;
        };
    }

    public static Mask states(Extent extent, BitSet ids) {
        return new StateMask(extent, ids);
    }

    public record StateMask(Extent extent, BitSet ids) implements Mask {
        @Override
        public boolean test(int x, int y, int z) {
            return ids.get(Block.getId(extent.get(x, y, z)));
        }
    }

    public static Mask existing(Extent extent) {
        return (x, y, z) -> !extent.get(x, y, z).isAir();
    }

    public static Mask air(Extent extent) {
        return (x, y, z) -> extent.get(x, y, z).isAir();
    }

    private static String arg(List<String> args, int index, String fallback) {
        return index < args.size() && !args.get(index).isEmpty() ? args.get(index) : fallback;
    }

    private static int intArg(List<String> args, int index, int fallback, int min, int max, String name) throws InputParseException {
        String value = arg(args, index, null);
        if (value == null) {
            return fallback;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", value);
        }
        if (parsed < min || parsed > max) {
            throw InputParseException.of("kawe.error.outside-range", name, min, max);
        }
        return parsed;
    }

    private static double percentArg(List<String> args, int index, double fallback) throws InputParseException {
        String value = arg(args, index, null);
        if (value == null) {
            return fallback;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", value);
        }
        if (!(parsed >= 0 && parsed <= 100)) {
            throw InputParseException.of("kawe.error.outside-range", "percent", 0, 100);
        }
        return parsed;
    }

    private static Mask region(List<String> args, ParseContext context) throws InputParseException {
        try {
            Region region = context.session().selector(context.world().bukkit()).region();
            return region::contains;
        } catch (IncompleteRegionException e) {
            throw InputParseException.of("kawe.error.incomplete-region");
        }
    }

    private static final MethodHandle LEGACY_SOLID;

    static {
        try {
            LEGACY_SOLID = MethodHandles.publicLookup().findVirtual(BlockBehaviour.BlockStateBase.class, "isSolid", MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static int nearestSurface(Extent extent, int x, int z, int y) {
        int at = Math.clamp(y, extent.minY(), extent.maxY());
        if (blocksMovement(extent.get(x, at, z))) {
            while (at < extent.maxY() && blocksMovement(extent.get(x, at + 1, z))) {
                at++;
            }
            return at;
        }
        while (at > extent.minY() && !blocksMovement(extent.get(x, at, z))) {
            at--;
        }
        return blocksMovement(extent.get(x, at, z)) ? at : Integer.MIN_VALUE;
    }

    public static boolean blocksMovement(BlockState state) {
        Block block = state.getBlock();
        return block != Blocks.COBWEB && block != Blocks.BAMBOO_SAPLING && solid(state);
    }

    public static boolean solid(BlockState state) {
        try {
            return (boolean) LEGACY_SOLID.invokeExact((BlockBehaviour.BlockStateBase) state);
        } catch (Throwable error) {
            throw new IllegalStateException("Could not read vanilla solidity", error);
        }
    }

    private static final BitSet PASSABLE = BlockMatcher.all(state -> state.isAir() || !blocksMovement(state));

    private static Mask surface(ParseContext context) {
        Extent extent = context.extent();
        int minY = extent.minY();
        int maxY = extent.maxY();
        return (x, y, z) -> {
            if (PASSABLE.get(Block.getId(extent.get(x, y, z)))) {
                return false;
            }
            return PASSABLE.get(Block.getId(extent.get(x + 1, y, z))) || PASSABLE.get(Block.getId(extent.get(x - 1, y, z)))
                    || PASSABLE.get(Block.getId(extent.get(x, y, z + 1))) || PASSABLE.get(Block.getId(extent.get(x, y, z - 1)))
                    || (y < maxY && PASSABLE.get(Block.getId(extent.get(x, y + 1, z))))
                    || (y > minY && PASSABLE.get(Block.getId(extent.get(x, y - 1, z))));
        };
    }

    private static Mask wall(ParseContext context) {
        Extent extent = context.extent();
        return (x, y, z) -> !extent.get(x, y, z).isAir()
                && (extent.get(x + 1, y, z).isAir() || extent.get(x - 1, y, z).isAir()
                || extent.get(x, y, z + 1).isAir() || extent.get(x, y, z - 1).isAir());
    }

    private static Mask negate(List<String> args, ParseContext context) throws InputParseException {
        if (args.isEmpty() || args.getFirst().isEmpty()) {
            throw InputParseException.of("kawe.error.parser.negate-nothing");
        }
        return parse(args.getFirst(), context).negate();
    }

    private static Mask offset(List<String> args, ParseContext context, int dy) throws InputParseException {
        Mask inner = args.isEmpty() || args.getFirst().isEmpty() ? existing(context.extent()) : parse(args.getFirst(), context);
        int minY = context.extent().minY();
        int maxY = context.extent().maxY();
        return (x, y, z) -> {
            int ty = y + dy;
            return ty >= minY && ty <= maxY && inner.test(x, ty, z);
        };
    }

    private static Mask noise(List<String> args, ParseContext context) throws InputParseException {
        double chance = percentArg(args, 0, 50) / 100.0;
        long seed = ThreadLocalRandom.current().nextLong();
        return (x, y, z) -> positional(seed, x, y, z) < chance;
    }

    public static double positional(long seed, int x, int y, int z) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static Mask biome(List<String> args, ParseContext context) throws InputParseException {
        String list = arg(args, 0, "");
        Set<Holder<Biome>> biomes = new HashSet<>();
        var registry = context.world().registries().lookupOrThrow(Registries.BIOME);
        for (String name : list.split(",")) {
            Identifier key = Identifier.tryParse(name.trim().toLowerCase(Locale.ROOT));
            var holder = key == null ? null : registry.get(net.minecraft.resources.ResourceKey.create(Registries.BIOME, key)).orElse(null);
            if (holder == null) {
                throw InputParseException.of("kawe.error.unknown-biome", name);
            }
            biomes.add(holder);
        }
        Extent extent = context.extent();
        return (x, y, z) -> biomes.contains(extent.biome(x, y, z));
    }

    private static Mask adjacent(List<String> args, ParseContext context) throws InputParseException {
        if (args.isEmpty()) {
            throw InputParseException.of("kawe.error.invalid-arguments", "~[mask][min][max]");
        }
        Mask inner = parse(args.getFirst(), context);
        int lo = intArg(args, 1, 1, 0, 6, "min");
        int hi = Math.min(args.size() > 2 ? intArg(args, 2, 6, 0, 8, "max") : (args.size() > 1 ? lo : 6), 6);
        if (lo > hi) {
            throw InputParseException.of("kawe.error.argument-size-mismatch", "min", "max");
        }
        return (x, y, z) -> {
            int count = 0;
            if (inner.test(x + 1, y, z)) {
                count++;
            }
            if (inner.test(x - 1, y, z)) {
                count++;
            }
            if (inner.test(x, y + 1, z)) {
                count++;
            }
            if (inner.test(x, y - 1, z)) {
                count++;
            }
            if (inner.test(x, y, z + 1)) {
                count++;
            }
            if (inner.test(x, y, z - 1)) {
                count++;
            }
            return count >= lo && count <= hi;
        };
    }

    private static Mask beside(List<String> args, ParseContext context) throws InputParseException {
        if (args.isEmpty()) {
            throw InputParseException.of("kawe.error.invalid-arguments", "#beside[mask][min][max]");
        }
        Mask inner = parse(args.getFirst(), context);
        int min = intArg(args, 1, 1, 0, 4, "min");
        int requestedMax = args.size() > 2 ? intArg(args, 2, 4, 0, 8, "max") : (args.size() > 1 ? min : 4);
        int max = Math.min(requestedMax, 4);
        if (min > max) {
            throw InputParseException.of("kawe.error.argument-size-mismatch", "min", "max");
        }
        return (x, y, z) -> {
            int count = 0;
            if (inner.test(x + 1, y, z)) {
                count++;
            }
            if (inner.test(x - 1, y, z)) {
                count++;
            }
            if (inner.test(x, y, z + 1)) {
                count++;
            }
            if (inner.test(x, y, z - 1)) {
                count++;
            }
            return count >= min && count <= max;
        };
    }

    private static Mask richOffset(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() < 4) {
            throw InputParseException.of("kawe.error.invalid-arguments", "#offset[dx][dy][dz][mask]");
        }
        int dx = intArg(args, 0, 0, -30_000_000, 30_000_000, "dx");
        int dy = intArg(args, 1, 0, -4096, 4096, "dy");
        int dz = intArg(args, 2, 0, -30_000_000, 30_000_000, "dz");
        Mask inner = parse(args.get(3), context);
        return (x, y, z) -> inner.test(x + dx, y + dy, z + dz);
    }

    private static Mask expression(List<String> args, ParseContext context) throws InputParseException {
        String source = arg(args, 0, "");
        Expression expression;
        try {
            expression = Expression.compile(source, "x", "y", "z");
        } catch (ExpressionException e) {
            throw InputParseException.of("kawe.error.parser.invalid-expression", e.getMessage());
        }
        WorldExprEnvironment environment = new WorldExprEnvironment(context.extent(), Vec3.ONE, Vec3.ZERO);
        expression.environment(environment);
        return (x, y, z) -> {
            environment.current(x, y, z);
            try {
                return expression.evaluate(x, y, z) > 0;
            } catch (ExpressionException e) {
                return false;
            }
        };
    }

    private static double doubleArg(String value) throws InputParseException {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException(value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", value);
        }
    }

    private static Mask adjacent2d(List<String> args, ParseContext context) throws InputParseException {
        if (args.isEmpty()) {
            throw InputParseException.of("kawe.error.invalid-arguments", "~2d[mask][min][max]");
        }
        Mask inner = parse(args.getFirst(), context);
        int min = intArg(args, 1, 1, 0, 4, "min");
        int max = Math.min(args.size() > 2 ? intArg(args, 2, 4, 0, 8, "max") : (args.size() > 1 ? min : 4), 4);
        if (min > max) {
            throw InputParseException.of("kawe.error.argument-size-mismatch", "min", "max");
        }
        return (x, y, z) -> {
            int count = (inner.test(x + 1, y, z) ? 1 : 0) + (inner.test(x - 1, y, z) ? 1 : 0)
                    + (inner.test(x, y, z + 1) ? 1 : 0) + (inner.test(x, y, z - 1) ? 1 : 0);
            return count >= min && count <= max;
        };
    }

    private enum Slope {
        ANGLE(1), EXTREMA(4), ROC(4);

        final int distance;

        Slope(int distance) {
            this.distance = distance;
        }
    }

    private static final double ADJACENT_MOD = 0.5;
    private static final double DIAGONAL_MOD = 1 / Math.sqrt(8);

    private static Mask slope(List<String> args, ParseContext context, Slope kind) throws InputParseException {
        if (args.size() < 2 || args.size() > 3) {
            throw InputParseException.of("kawe.error.invalid-arguments", "[min][max][-o]");
        }
        String minArg = args.get(0).trim();
        String maxArg = args.get(1).trim();
        boolean degree = minArg.endsWith("d");
        if (degree != maxArg.endsWith("d")) {
            throw InputParseException.of("kawe.error.mask.angle");
        }
        boolean overlay = false;
        if (args.size() == 3) {
            if (!args.get(2).trim().equals("-o")) {
                throw InputParseException.of("kawe.error.invalid-flag", args.get(2));
            }
            overlay = true;
        }
        double min = degree ? slopeOf(minArg) : doubleArg(minArg);
        double max = degree ? slopeOf(maxArg) : doubleArg(maxArg);
        Extent extent = context.extent();
        boolean onlySurface = overlay;
        int d = kind.distance;
        return (x, y, z) -> {
            if (!solid(extent.get(x, y, z))) {
                return false;
            }
            if (onlySurface && y < extent.maxY() && !besideNonSolid(extent, x, y, z)) {
                return false;
            }
            return switch (kind) {
                case ANGLE -> {
                    double slope = Math.abs(height(extent, x + d, y, z) - height(extent, x - d, y, z)) * ADJACENT_MOD;
                    slope = Math.max(slope, Math.abs(height(extent, x, y, z + d) - height(extent, x, y, z - d)) * ADJACENT_MOD);
                    slope = Math.max(slope, Math.abs(height(extent, x + d, y, z + d) - height(extent, x - d, y, z - d)) * DIAGONAL_MOD);
                    slope = Math.max(slope, Math.abs(height(extent, x - d, y, z + d) - height(extent, x + d, y, z - d)) * DIAGONAL_MOD);
                    yield slope >= min && slope <= max;
                }
                case ROC -> {
                    int base = height(extent, x, y, z);
                    double slope = curvature(extent, base, x, y, z, d, 0) * ADJACENT_MOD;
                    slope = steeper(slope, curvature(extent, base, x, y, z, 0, d) * ADJACENT_MOD);
                    slope = steeper(slope, curvature(extent, base, x, y, z, d, d) * DIAGONAL_MOD);
                    slope = steeper(slope, curvature(extent, base, x, y, z, -d, d) * DIAGONAL_MOD);
                    yield slope >= min && slope <= max;
                }
                case EXTREMA -> {
                    int base = height(extent, x, y, z);
                    double slope = extremum(extent, base, x, y, z, 1, 0, d) * ADJACENT_MOD;
                    slope = steeper(slope, extremum(extent, base, x, y, z, 0, 1, d) * ADJACENT_MOD);
                    slope = steeper(slope, extremum(extent, base, x, y, z, 1, 1, d) * DIAGONAL_MOD);
                    slope = steeper(slope, extremum(extent, base, x, y, z, 1, -1, d) * DIAGONAL_MOD);
                    yield slope > min && slope < max;
                }
            };
        };
    }

    private static double steeper(double current, double candidate) {
        return Math.abs(candidate) > Math.abs(current) ? candidate : current;
    }

    private static double curvature(Extent extent, int base, int x, int y, int z, int dx, int dz) {
        return height(extent, x + dx, y, z + dz) - base - (base - height(extent, x - dx, y, z - dz));
    }

    private static int extremum(Extent extent, int base, int x, int y, int z, int ox, int oz, int iterations) {
        int sign = 0;
        int last1 = base;
        int last2 = base;
        for (int i = 1; i <= iterations; i++) {
            int height1 = height(extent, x + ox * i, y, z + oz * i);
            int height2 = height(extent, x - ox * i, y, z - oz * i);
            int sign1 = Integer.signum(height1 - last1);
            int sign2 = Integer.signum(height2 - last2);
            if (sign == 0) {
                sign = sign1 != 0 ? sign1 : sign2;
            }
            if (sign1 == 0) {
                sign1 = sign;
            }
            if (sign2 == 0) {
                sign2 = sign;
            }
            if (sign1 != sign2) {
                break;
            }
            last1 = height1;
            last2 = height2;
        }
        return (last1 - base) + (last2 - base);
    }

    private static boolean besideNonSolid(Extent extent, int x, int y, int z) {
        if (y != extent.maxY() && !solid(extent.get(x, y + 1, z))) {
            return true;
        }
        if (y != extent.minY() && !solid(extent.get(x, y - 1, z))) {
            return true;
        }
        return !solid(extent.get(x + 1, y, z)) || !solid(extent.get(x - 1, y, z)) || !solid(extent.get(x, y, z + 1)) || !solid(extent.get(x, y, z - 1));
    }

    public static int height(Extent extent, int x, int y, int z) {
        return surface(extent, x, z, y, extent.minY(), extent.maxY());
    }

    public static int surface(Extent extent, int x, int z, int y, int lowest, int highest) {
        int minY = Math.max(lowest, extent.minY());
        int maxY = Math.min(highest, extent.maxY());
        int start = Math.clamp(y, minY, maxY);
        boolean open = !solid(extent.get(x, start, z));
        int offset = open ? 0 : 1;
        int above = maxY - start;
        int below = start - minY;
        int clearance = Math.min(above, below);
        for (int d = 0; d <= clearance; d++) {
            if (solid(extent.get(x, start + d, z)) == open) {
                return start + d - offset;
            }
            if (solid(extent.get(x, start - d, z)) == open) {
                return start - d + offset;
            }
        }
        if (above < below) {
            for (int layer = start - clearance - 1; layer >= minY; layer--) {
                if (solid(extent.get(x, layer, z)) == open) {
                    return layer + offset;
                }
            }
        } else if (above > below) {
            for (int layer = start + clearance + 1; layer <= maxY; layer++) {
                if (solid(extent.get(x, layer, z)) == open) {
                    return layer - offset;
                }
            }
        }
        return open ? minY : maxY;
    }

    private static Mask radius(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() != 2) {
            throw InputParseException.of("kawe.error.invalid-arguments", "{[min][max]");
        }
        BlockVec anchor = context.anchor();
        if (anchor == null) {
            throw InputParseException.of("kawe.error.parser.player-only", "{");
        }
        long min = intArg(args, 0, 0, 0, Integer.MAX_VALUE, "min");
        long max = intArg(args, 1, 0, 0, Integer.MAX_VALUE, "max");
        long minSqr = min * min;
        long maxSqr = max * max;
        return (x, y, z) -> {
            long dx = x - anchor.x();
            long dy = y - anchor.y();
            long dz = z - anchor.z();
            long d = dx * dx + dy * dy + dz * dz;
            return d >= minSqr && d <= maxSqr;
        };
    }

    private static Mask simplex(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() < 3 || args.size() > 4) {
            throw InputParseException.of("kawe.error.invalid-arguments", "#simplex[scale][min][max][octaves]");
        }
        double scale = 1d / Math.max(1d, doubleArg(args.get(0)));
        double min = (doubleArg(args.get(1)) - 50d) / 50d;
        double max = (doubleArg(args.get(2)) - 50d) / 50d;
        int octaves = intArg(args, 3, 1, 1, 16, "octaves");
        Simplex noise = new Simplex(0);
        return (x, y, z) -> {
            double value = noise.fractal(x * scale, y * scale, z * scale, octaves, 2.0, 0.5);
            return value >= min && value <= max;
        };
    }

    private static double slopeOf(String degrees) throws InputParseException {
        double value = doubleArg(degrees.substring(0, degrees.length() - 1));
        if (value < -90 || value > 90) {
            throw new InputParseException(Component.text("Angles must be between -90 and 90 degrees: " + degrees));
        }
        return Math.tan(Math.toRadians(value));
    }

    private static Mask surfaceAngle(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() < 2 || args.size() > 3) {
            throw InputParseException.of("kawe.error.invalid-arguments", "#surfaceangle[min][max][size]");
        }
        double min = doubleArg(args.get(0));
        double max = doubleArg(args.get(1));
        int size = intArg(args, 2, 1, 1, 16, "size");
        Extent extent = context.extent();
        return (x, y, z) -> {
            if (extent.get(x, y, z).isAir() || !besideAir(extent, x, y, z)) {
                return false;
            }
            double sumX = 0;
            double sumY = 0;
            double sumZ = 0;
            int count = 0;
            for (int i = -size; i <= size; i++) {
                for (int j = -size; j <= size; j++) {
                    if (y + j < extent.minY() || y + j > extent.maxY()) {
                        continue;
                    }
                    for (int k = -size; k <= size; k++) {
                        if (extent.get(x + i, y + j, z + k).isAir()) {
                            sumX += i;
                            sumY += j;
                            sumZ += k;
                            count++;
                        }
                    }
                }
            }
            double length = Math.sqrt(sumX * sumX + sumY * sumY + sumZ * sumZ);
            double up = count == 0 || length == 0 ? 0 : sumY / length;
            double angle = Math.toDegrees(Math.acos(Math.clamp(up, -1, 1)));
            return angle >= min && angle <= max;
        };
    }

    private static boolean besideAir(Extent extent, int x, int y, int z) {
        return extent.get(x + 1, y, z).isAir() || extent.get(x - 1, y, z).isAir() || extent.get(x, y + 1, z).isAir()
                || extent.get(x, y - 1, z).isAir() || extent.get(x, y, z + 1).isAir() || extent.get(x, y, z - 1).isAir();
    }

    private static Mask hotbar(ParseContext context) throws InputParseException {
        if (!context.actor().isPlayer()) {
            throw InputParseException.of("kawe.error.parser.player-only", "#hotbar");
        }
        List<Block> blocks = context.actor().hotbarBlocks();
        if (blocks.isEmpty()) {
            throw InputParseException.of("kawe.error.no-valid-on-hotbar");
        }
        Set<Block> set = Set.copyOf(blocks);
        return states(context.extent(), BlockMatcher.all(state -> set.contains(state.getBlock())));
    }

    private static Mask blockStates(List<String> args, ParseContext context, boolean strict) throws InputParseException {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String part : String.join(",", args).split(",")) {
            if (part.isBlank()) {
                continue;
            }
            String[] kv = part.split("=", 2);
            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                throw InputParseException.of("kawe.error.parser.bad-state-format", part);
            }
            String name = kv[0].trim().toLowerCase(Locale.ROOT);
            if (!PropertyNames.ALL.contains(name)) {
                throw InputParseException.of("kawe.error.parser.unknown-property", name, strict ? "^=" : "^");
            }
            wanted.put(name, kv[1].trim().toLowerCase(Locale.ROOT));
        }
        if (wanted.isEmpty()) {
            throw InputParseException.of("kawe.error.invalid-arguments", strict ? "^=[property=value]" : "^[property=value]");
        }
        return states(context.extent(), BlockMatcher.all(state -> {
            for (Map.Entry<String, String> entry : wanted.entrySet()) {
                Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null || property.getValue(entry.getValue()).isEmpty()) {
                    if (strict) {
                        return false;
                    }
                    continue;
                }
                if (!BlockMatcher.valueName(state, property).equals(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }));
    }

    static final class PropertyNames {
        static final Set<String> ALL = names();

        static boolean validValue(String name, String value) {
            for (Block block : BuiltInRegistries.BLOCK) {
                Property<?> property = block.getStateDefinition().getProperty(name);
                if (property != null && property.getValue(value).isPresent()) {
                    return true;
                }
            }
            return false;
        }

        private static Set<String> names() {
            Set<String> out = new HashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                for (Property<?> property : block.getStateDefinition().getProperties()) {
                    out.add(property.getName());
                }
            }
            return Set.copyOf(out);
        }
    }

    private static Mask axis(ParseContext context, int axis) throws InputParseException {
        BlockVec anchor = context.anchor();
        if (anchor == null) {
            throw InputParseException.of("kawe.error.parser.player-only", axis == 0 ? "#xaxis" : axis == 1 ? "#yaxis" : "#zaxis");
        }
        int layer = axis == 0 ? anchor.x() : axis == 1 ? anchor.y() : anchor.z();
        return (x, y, z) -> (axis == 0 ? x : axis == 1 ? y : z) == layer;
    }
}
