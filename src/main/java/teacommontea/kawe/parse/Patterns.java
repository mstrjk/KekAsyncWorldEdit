package teacommontea.kawe.parse;

import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import teacommontea.kawe.Settings;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionException;
import teacommontea.kawe.expr.Noise;
import teacommontea.kawe.expr.WorldExprEnvironment;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Simplex;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.op.ClipboardOps;
import teacommontea.kawe.schematic.Schematics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class Patterns {

    @FunctionalInterface
    public interface Factory {
        Pattern create(List<String> args, ParseContext context) throws InputParseException;
    }

    private static final java.util.regex.Pattern PERCENT = java.util.regex.Pattern.compile("[0-9]+(\\.[0-9]*)?%.*");
    private static final Map<String, Factory> FACTORIES = new LinkedHashMap<>();

    static {
        register((args, c) -> existing(c.extent()), "#existing");
        register(Patterns::expression, "=");
        register(Patterns::biome, "#biome", "$");
        register((args, c) -> buffered(args, c, false), "#buffer");
        register((args, c) -> buffered(args, c, true), "#buffer2d");
        register(Patterns::linear, "#linear", "#l");
        register((args, c) -> linearScaled(args, c, false), "#linear2d", "#l2d");
        register((args, c) -> linearScaled(args, c, true), "#linear3d", "#l3d");
        register(Patterns::masked, "#mask");
        register((args, c) -> flatten(args, c, 0), "#!x", "#nx", "#nox");
        register((args, c) -> flatten(args, c, 1), "#!y", "#ny", "#noy");
        register((args, c) -> flatten(args, c, 2), "#!z", "#nz", "#noz");
        register(Patterns::offset, "#offset");
        register((args, c) -> noise(args, c, Noises.PERLIN), "#perlin");
        register((args, c) -> noise(args, c, Noises.RIDGED), "#ridgedmulti");
        register((args, c) -> noise(args, c, Noises.VORONOI), "#voronoi");
        register((args, c) -> noise(args, c, Noises.SIMPLEX), "#simplex");
        register((args, c) -> spread(args, c, false), "#spread", "#randomoffset");
        register((args, c) -> spread(args, c, true), "#solidspread");
        register(Patterns::surfaceSpread, "#surfacespread");
        register(Patterns::relative, "#relative", "#~", "#r", "#rel");
        register(Patterns::typeSwap, "#typeswap", "#ts", "#swaptype");
        register(Patterns::hotbar, "#hotbar");
        register(Patterns::clipboard, "#clipboard", "#copy", "#clipboard@", "#copy@");
        ColorPatterns.register();
        register(Patterns::fullCopy, "#fullcopy");
    }

    private Patterns() {
    }

    public static void register(Factory factory, String... aliases) {
        for (String alias : aliases) {
            FACTORIES.put(alias, factory);
        }
    }

    public static Set<String> aliases() {
        return FACTORIES.keySet();
    }

    public static Pattern parse(String input, ParseContext context) throws InputParseException {
        if (input == null || input.isEmpty()) {
            throw InputParseException.of("kawe.error.invalid-arguments", "pattern");
        }
        List<Double> chances = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        for (Input.Entry entry : Input.split(input)) {
            String command = entry.command();
            String full = entry.full();
            Pattern pattern = null;
            double chance = 1;
            if (command.isEmpty()) {
                pattern = parse(String.join(",", entry.args()), context);
            } else if (FACTORIES.containsKey(command.toLowerCase(Locale.ROOT))) {
                pattern = FACTORIES.get(command.toLowerCase(Locale.ROOT)).create(entry.args(), context);
            } else {
                char first = command.charAt(0);
                boolean charPattern = input.length() > 1 && input.charAt(1) != '[';
                if (charPattern && input.charAt(0) == '=' && FACTORIES.containsKey("=")) {
                    pattern = FACTORIES.get("=").create(List.of(input.substring(1)), context);
                }
                if (pattern == null && first == '#' && command.length() > 1 && command.charAt(1) != '#') {
                    throw InputParseException.of("kawe.error.parse.unknown-pattern", full, "");
                }
                if (pattern == null && first == '^' && full.length() > 1) {
                    pattern = applying(full.substring(1), context);
                }
                if (pattern == null && charPattern) {
                    if (first == '*') {
                        pattern = randomState(full.substring(1), context);
                    } else if (first == '#' && command.startsWith("##")) {
                        pattern = tag(full.substring(2));
                    } else if (first == '$' && FACTORIES.containsKey("$")) {
                        pattern = FACTORIES.get("$").create(List.of(full.substring(1)), context);
                    }
                }
                if (pattern == null) {
                    if (command.startsWith("[") && Input.matchingBracket(command, 0) == command.length() - 1) {
                        pattern = parse(command.substring(1, command.length() - 1), context);
                    } else {
                        int percent = command.indexOf('%');
                        if (percent != -1 && PERCENT.matcher(command).matches()) {
                            chance = Double.parseDouble(command.substring(0, percent));
                            String value = command.substring(percent + 1);
                            if (!entry.args().isEmpty()) {
                                boolean brackets = !value.isEmpty();
                                value = (brackets ? value + "[" : value) + String.join("][", entry.args()) + (brackets ? "]" : "");
                            }
                            pattern = parse(value, context);
                        } else {
                            pattern = block(full, context);
                        }
                    }
                }
            }
            patterns.add(pattern);
            chances.add(chance);
        }
        if (patterns.size() == 1) {
            return patterns.getFirst();
        }
        return weighted(patterns, chances);
    }

    public static Pattern block(String input, ParseContext context) throws InputParseException {
        BlockInput.Parsed parsed = BlockInput.parse(input, context);
        CompoundTag nbt = parsed.nbt();
        if (nbt == null || nbt.isEmpty()) {
            return Pattern.of(parsed.state());
        }
        return new NbtPattern(parsed.state(), nbt);
    }

    public record NbtPattern(BlockState block, CompoundTag nbt) implements Pattern {
        @Override
        public BlockState state(int x, int y, int z) {
            return block;
        }

        @Override
        public CompoundTag tile(int x, int y, int z) {
            return nbt.copy();
        }

        @Override
        public boolean apply(Extent dest, int x, int y, int z) {
            return dest.set(x, y, z, block, nbt.copy());
        }
    }

    public interface Source {
        double nextDouble(int x, int y, int z);

        default double nextDouble(int x, int y, int z, double bound) {
            return nextDouble(x, y, z) * bound;
        }

        default int nextInt(int x, int y, int z, int bound) {
            return (int) nextDouble(x, y, z, bound);
        }
    }

    public static final Source TRUE_RANDOM = new Source() {
        @Override
        public double nextDouble(int x, int y, int z) {
            return ThreadLocalRandom.current().nextDouble();
        }

        @Override
        public int nextInt(int x, int y, int z, int bound) {
            return ThreadLocalRandom.current().nextInt(bound);
        }
    };

    private static final int FAST_TABLE_LIMIT = 100_000;

    public static final class Weighted implements Pattern {
        private final Map<Pattern, Double> weights;
        private final Source source;
        private final Pattern[] fast;
        private final double[] cumulative;
        private final Pattern[] options;
        private final double total;

        Weighted(Map<Pattern, Double> weights, Source source) {
            this.weights = weights;
            this.source = source;
            this.fast = fastTable(weights);
            this.options = weights.keySet().toArray(Pattern[]::new);
            this.cumulative = new double[options.length];
            double sum = 0;
            for (int i = 0; i < options.length; i++) {
                double weight = weights.get(options[i]);
                sum += weight > 0 ? weight : 0;
                cumulative[i] = sum;
            }
            this.total = sum;
        }

        private static Pattern[] fastTable(Map<Pattern, Double> weights) {
            int[] counts = new int[weights.size()];
            int max = 0;
            int index = 0;
            for (double weight : weights.values()) {
                int scaled = (int) (weight * 100);
                if (scaled != weight * 100 || scaled <= 0) {
                    return null;
                }
                counts[index++] = scaled;
                max = Math.max(max, scaled);
            }
            int gcd = 0;
            for (int count : counts) {
                gcd = gcd(gcd, count);
            }
            long size = 0;
            for (int count : counts) {
                size += count / gcd;
            }
            if (max / gcd > FAST_TABLE_LIMIT || size > FAST_TABLE_LIMIT) {
                return null;
            }
            List<Pattern> table = new ArrayList<>();
            index = 0;
            for (Pattern pattern : weights.keySet()) {
                for (int j = 0; j < counts[index] / gcd; j++) {
                    table.add(pattern);
                }
                index++;
            }
            return table.toArray(Pattern[]::new);
        }

        private static int gcd(int a, int b) {
            return b == 0 ? a : gcd(b, a % b);
        }

        public Weighted with(Source next) {
            return new Weighted(weights, next);
        }

        public List<Pattern> distinct() {
            return List.of(options);
        }

        private Pattern choose(int x, int y, int z) {
            if (fast != null) {
                return fast[source.nextInt(x, y, z, fast.length)];
            }
            double roll = source.nextDouble(x, y, z, total);
            for (int i = 0; i < cumulative.length; i++) {
                if (roll < cumulative[i]) {
                    return options[i];
                }
            }
            for (int i = cumulative.length - 1; i > 0; i--) {
                if (cumulative[i] > cumulative[i - 1]) {
                    return options[i];
                }
            }
            return options[0];
        }

        @Override
        public BlockState state(int x, int y, int z) {
            return choose(x, y, z).state(x, y, z);
        }

        @Override
        public Pattern pick(int x, int y, int z) {
            return choose(x, y, z).pick(x, y, z);
        }

        @Override
        public boolean apply(Extent dest, int x, int y, int z) {
            return choose(x, y, z).apply(dest, x, y, z);
        }
    }

    public static Pattern weighted(List<Pattern> patterns, List<Double> chances) {
        Map<Pattern, Double> weights = new LinkedHashMap<>();
        for (int i = 0; i < patterns.size(); i++) {
            weights.merge(patterns.get(i), chances.get(i), Double::sum);
        }
        return new Weighted(weights, TRUE_RANDOM);
    }

    private static Pattern expression(List<String> args, ParseContext context) throws InputParseException {
        String source = args.isEmpty() ? "" : args.getFirst();
        Expression expression;
        try {
            expression = Expression.compile(source, "x", "y", "z");
        } catch (ExpressionException e) {
            throw InputParseException.of("kawe.error.parser.invalid-expression", e.getMessage());
        }
        WorldExprEnvironment environment = new WorldExprEnvironment(context.extent(), Vec3.ONE, Vec3.ZERO);
        expression.environment(environment);
        BlockState air = Blocks.AIR.defaultBlockState();
        return (x, y, z) -> {
            environment.current(x, y, z);
            try {
                BlockState state = Block.stateById((int) expression.evaluate(x, y, z));
                return state == null ? air : state;
            } catch (ExpressionException e) {
                return air;
            }
        };
    }

    private static Pattern inner(List<String> args, int index, ParseContext context, String usage) throws InputParseException {
        if (args.size() <= index || args.get(index).isBlank()) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        return parse(args.get(index), context);
    }

    private static int intArg(List<String> args, int index, String usage) throws InputParseException {
        if (args.size() <= index) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        try {
            return Integer.parseInt(args.get(index).trim());
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", args.get(index));
        }
    }

    private static double doubleArg(List<String> args, int index, String usage) throws InputParseException {
        if (args.size() <= index) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        try {
            double value = Double.parseDouble(args.get(index).trim());
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", args.get(index));
        }
    }

    @FunctionalInterface
    private interface Mapping {
        boolean map(int x, int y, int z, int[] out);
    }

    private static Pattern resample(Pattern inner, Mapping mapping) {
        BlockState air = Blocks.AIR.defaultBlockState();
        return new Pattern() {
            @Override
            public BlockState state(int x, int y, int z) {
                int[] at = new int[3];
                return mapping.map(x, y, z, at) ? inner.state(at[0], at[1], at[2]) : air;
            }

            @Override
            public CompoundTag tile(int x, int y, int z) {
                int[] at = new int[3];
                return mapping.map(x, y, z, at) ? inner.tile(at[0], at[1], at[2]) : null;
            }

            @Override
            public Pattern pick(int x, int y, int z) {
                int[] at = new int[3];
                if (!mapping.map(x, y, z, at)) {
                    return Pattern.of(air);
                }
                Pattern picked = inner.pick(at[0], at[1], at[2]);
                BlockState state = picked.state(at[0], at[1], at[2]);
                CompoundTag tag = picked.tile(at[0], at[1], at[2]);
                return new Pattern() {
                    @Override
                    public BlockState state(int px, int py, int pz) {
                        return state;
                    }

                    @Override
                    public CompoundTag tile(int px, int py, int pz) {
                        return tag;
                    }
                };
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                Pattern picked = pick(x, y, z);
                BlockState state = picked.state(x, y, z);
                return state != null && dest.set(x, y, z, state, picked.tile(x, y, z));
            }
        };
    }

    private static Pattern biome(List<String> args, ParseContext context) throws InputParseException {
        String name = args.isEmpty() ? "" : args.getFirst().trim().toLowerCase(Locale.ROOT);
        Identifier id = Identifier.tryParse(name);
        Holder<Biome> holder = id == null ? null
                : context.world().registries().lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, id)).orElse(null);
        if (holder == null) {
            throw InputParseException.of("kawe.error.unknown-biome", name);
        }
        Extent extent = context.extent();
        return new Pattern() {
            @Override
            public BlockState state(int x, int y, int z) {
                return extent.get(x, y, z);
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                return dest.setBiome(x, y, z, holder);
            }
        };
    }

    private static Pattern buffered(List<String> args, ParseContext context, boolean flat) throws InputParseException {
        Pattern inner = inner(args, 0, context, flat ? "#buffer2d[pattern]" : "#buffer[pattern]");
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        AtomicReference<Extent> owner = new AtomicReference<>();
        return new Pattern() {
            @Override
            public BlockState state(int x, int y, int z) {
                return inner.state(x, y, z);
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                if (owner.get() != dest) {
                    synchronized (seen) {
                        if (owner.get() != dest) {
                            seen.clear();
                            owner.set(dest);
                        }
                    }
                }
                long key = BlockPos.asLong(x, flat ? 0 : y, z);
                if (!seen.add(key)) {
                    return false;
                }
                if (inner.apply(dest, x, y, z)) {
                    return true;
                }
                seen.remove(key);
                return false;
            }
        };
    }

    private static Pattern linear(List<String> args, ParseContext context) throws InputParseException {
        Pattern inner = inner(args, 0, context, "#linear[pattern]");
        if (!(inner instanceof Weighted weighted)) {
            return single(inner, "#linear");
        }
        Pattern[] options = weighted.distinct().toArray(Pattern[]::new);
        AtomicInteger index = new AtomicInteger();
        return new Pattern() {
            private Pattern next() {
                return options[Math.floorMod(index.incrementAndGet(), options.length)];
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return next().state(x, y, z);
            }

            @Override
            public Pattern pick(int x, int y, int z) {
                return next().pick(x, y, z);
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                return next().apply(dest, x, y, z);
            }
        };
    }

    private static Pattern single(Pattern inner, String name) throws InputParseException {
        if (inner instanceof Pattern.Single || inner instanceof NbtPattern) {
            return inner;
        }
        throw new InputParseException(Component.text("Pattern " + inner.getClass().getSimpleName() + " cannot be used with " + name));
    }

    private static Pattern linearScaled(List<String> args, ParseContext context, boolean threeD) throws InputParseException {
        String name = threeD ? "#linear3d" : "#linear2d";
        Pattern inner = inner(args, 0, context, name + "[pattern]");
        if (!(inner instanceof Weighted weighted)) {
            return single(inner, name);
        }
        int xScale = args.size() > 1 ? intArg(args, 1, name) : 1;
        int second = args.size() > 2 ? intArg(args, 2, name) : 1;
        int third = threeD && args.size() > 3 ? intArg(args, 3, name) : 1;
        if (xScale == 0 || second == 0 || third == 0) {
            throw InputParseException.of("kawe.error.outside-range", "scale", "1", "-1");
        }
        int yScale = threeD ? second : 1;
        int zScale = threeD ? third : second;
        return weighted.with(new Source() {
            @Override
            public double nextDouble(int x, int y, int z) {
                return nextDouble(x, y, z, 1d);
            }

            @Override
            public double nextDouble(int x, int y, int z, double bound) {
                double sum = Math.nextUp((double) x) / xScale + Math.nextUp((double) z) / zScale;
                if (threeD) {
                    sum += Math.nextUp((double) y) / yScale;
                }
                double index = sum % bound;
                return index < 0 ? index + bound : index;
            }

            @Override
            public int nextInt(int x, int y, int z, int bound) {
                int sum = Math.floorDiv(x, xScale) + Math.floorDiv(z, zScale) + (threeD ? Math.floorDiv(y, yScale) : 0);
                return Math.floorMod(sum, bound);
            }
        });
    }

    private static Pattern masked(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() != 3) {
            throw InputParseException.of("kawe.error.command.syntax", "#mask[mask][pattern][pattern]");
        }
        Mask mask = Masks.parse(args.get(0), context);
        Pattern primary = parse(args.get(1), context);
        Pattern secondary = parse(args.get(2), context);
        return new Pattern() {
            private Pattern route(int x, int y, int z) {
                return mask.test(x, y, z) ? primary : secondary;
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return route(x, y, z).state(x, y, z);
            }

            @Override
            public Pattern pick(int x, int y, int z) {
                return route(x, y, z).pick(x, y, z);
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                return route(x, y, z).apply(dest, x, y, z);
            }
        };
    }

    private static Pattern flatten(List<String> args, ParseContext context, int axis) throws InputParseException {
        Pattern inner = inner(args, 0, context, "[pattern]");
        return resample(inner, (x, y, z, out) -> {
            out[0] = axis == 0 ? 0 : x;
            out[1] = axis == 1 ? 0 : y;
            out[2] = axis == 2 ? 0 : z;
            return true;
        });
    }

    private static int[] offsets(List<String> args, String usage) throws InputParseException {
        if (args.size() == 4) {
            return new int[]{intArg(args, 1, usage), intArg(args, 2, usage), intArg(args, 3, usage)};
        }
        if (args.size() == 2) {
            int all = intArg(args, 1, usage);
            return new int[]{all, all, all};
        }
        throw InputParseException.of("kawe.error.command.syntax", usage);
    }

    private static Pattern offset(List<String> args, ParseContext context) throws InputParseException {
        String usage = "#offset[pattern][x][y][z]";
        Pattern inner = inner(args, 0, context, usage);
        int[] d = offsets(args, usage);
        Extent extent = context.extent();
        return resample(inner, (x, y, z, out) -> {
            out[0] = x + d[0];
            out[1] = y + d[1];
            out[2] = z + d[2];
            return out[1] >= extent.minY() && out[1] <= extent.maxY();
        });
    }

    private enum Noises {
        PERLIN, RIDGED, VORONOI, SIMPLEX
    }

    private static Pattern noise(List<String> args, ParseContext context, Noises kind) throws InputParseException {
        String usage = "#" + kind.name().toLowerCase(Locale.ROOT) + (kind == Noises.SIMPLEX ? "[scale][pattern][octaves]" : "[scale][pattern]");
        if (args.size() != 2 && !(kind == Noises.SIMPLEX && args.size() == 3)) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        double scale = 1d / Math.max(1, doubleArg(args, 0, usage));
        Pattern inner = inner(args, 1, context, usage);
        if (!(inner instanceof Weighted weighted)) {
            return single(inner, "#" + kind.name().toLowerCase(Locale.ROOT));
        }
        int seed = ThreadLocalRandom.current().nextInt();
        int octaves = args.size() == 3 ? Math.clamp(intArg(args, 2, usage), 1, 16) : 1;
        Simplex simplex = new Simplex(seed);
        return weighted.with((x, y, z) -> {
            double sx = x * scale;
            double sy = y * scale;
            double sz = z * scale;
            double raw = switch (kind) {
                case PERLIN -> Noise.perlin(seed, sx, sy, sz, 1.0, 6, 0.5);
                case RIDGED -> Noise.ridged(seed, sx, sy, sz, 1.0, 6);
                case VORONOI -> Noise.voronoi(seed, sx, sy, sz, 1.0);
                case SIMPLEX -> simplex.fractal(sx, sy, sz, octaves, 2.0, 0.5);
            };
            float value = kind == Noises.SIMPLEX ? (float) ((raw + 1) * 0.5) : (float) Math.max(0, Math.min(1, raw / 2.0 + 0.5));
            return value >= 1.0 ? 0x1.fffffffffffffp-1 : value;
        });
    }

    private static Pattern spread(List<String> args, ParseContext context, boolean solidOnly) throws InputParseException {
        String usage = (solidOnly ? "#solidspread" : "#spread") + "[pattern][x][y][z]";
        Pattern inner = inner(args, 0, context, usage);
        int[] d = offsets(args, usage);
        for (int value : d) {
            if (value < 0 || value > MAX_SPREAD) {
                throw InputParseException.of("kawe.error.outside-range", "offset", "0", String.valueOf(MAX_SPREAD));
            }
        }
        Extent extent = context.extent();
        return resample(inner, (x, y, z, out) -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            long lx = (long) x + random.nextInt(d[0] * 2 + 1) - d[0];
            long ly = (long) y + random.nextInt(d[1] * 2 + 1) - d[1];
            long lz = (long) z + random.nextInt(d[2] * 2 + 1) - d[2];
            if (ly < extent.minY() || ly > extent.maxY() || lx != (int) lx || lz != (int) lz) {
                return false;
            }
            int rx = (int) lx;
            int ry = (int) ly;
            int rz = (int) lz;
            if (solidOnly) {
                BlockState sampled = inner.state(rx, ry, rz);
                if (sampled == null || !Masks.solid(sampled)) {
                    out[0] = x;
                    out[1] = y;
                    out[2] = z;
                    return true;
                }
            }
            out[0] = rx;
            out[1] = ry;
            out[2] = rz;
            return true;
        });
    }

    private static final int MAX_SPREAD = 1 << 20;
    private static final int[][] DIAGONALS = diagonals();

    private static int[][] diagonals() {
        List<int[]> list = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        list.add(new int[]{x, y, z});
                    }
                }
            }
        }
        list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1] + a[2] * a[2], b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        return list.toArray(int[][]::new);
    }

    private static Pattern surfaceSpread(List<String> args, ParseContext context) throws InputParseException {
        String usage = "#surfacespread[pattern][distance]";
        Pattern inner = inner(args, 0, context, usage);
        int moves = Math.min(255, intArg(args, 1, usage));
        Extent extent = context.extent();
        return resample(inner, (x, y, z, out) -> {
            int cx = x;
            int cy = y;
            int cz = z;
            int[][] allowed = new int[DIAGONALS.length][];
            for (int move = 0; move < moves; move++) {
                int count = 0;
                for (int[] dir : DIAGONALS) {
                    int nx = cx + dir[0];
                    int ny = cy + dir[1];
                    int nz = cz + dir[2];
                    if (ny < extent.minY() || ny > extent.maxY()) {
                        continue;
                    }
                    if (surfaceStep(inner, extent, nx, ny, nz)) {
                        allowed[count++] = new int[]{nx, ny, nz};
                    }
                }
                if (count == 0) {
                    break;
                }
                int[] next = allowed[ThreadLocalRandom.current().nextInt(count)];
                cx = next[0];
                cy = next[1];
                cz = next[2];
            }
            out[0] = cx;
            out[1] = cy;
            out[2] = cz;
            return true;
        });
    }

    private static boolean passable(Pattern pattern, int x, int y, int z) {
        BlockState state = pattern.state(x, y, z);
        return state == null || !Masks.blocksMovement(state);
    }

    private static boolean surfaceStep(Pattern pattern, Extent extent, int x, int y, int z) {
        if (passable(pattern, x, y, z)) {
            return false;
        }
        return (y < extent.maxY() && passable(pattern, x, y + 1, z)) || (y > extent.minY() && passable(pattern, x, y - 1, z))
                || passable(pattern, x + 1, y, z) || passable(pattern, x - 1, y, z) || passable(pattern, x, y, z + 1) || passable(pattern, x, y, z - 1);
    }

    private static Pattern relative(List<String> args, ParseContext context) throws InputParseException {
        Pattern inner = inner(args, 0, context, "#relative[pattern]");
        BlockVec anchor = context.anchor();
        if (anchor == null) {
            throw InputParseException.of("kawe.error.parser.player-only", "#relative");
        }
        Extent extent = context.extent();
        return resample(inner, (x, y, z, out) -> {
            out[0] = x - anchor.x();
            out[1] = y - anchor.y();
            out[2] = z - anchor.z();
            return out[1] >= extent.minY() && out[1] <= extent.maxY();
        });
    }

    private static final java.util.regex.Pattern PLAIN = java.util.regex.Pattern.compile("[A-Za-z0-9_]*");

    private static Pattern typeSwap(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() != 2) {
            throw InputParseException.of("kawe.error.command.syntax", "#typeswap[input][output] (e.g. #typeswap[spruce][oak])");
        }
        String from = args.get(0);
        String to = args.get(1);
        boolean regex = !PLAIN.matcher(from).matches() && context.actor().has("kawe.pattern.typeswap.regex");
        java.util.regex.Pattern compiled;
        try {
            compiled = regex ? java.util.regex.Pattern.compile(from.replace(",", "|")) : null;
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new InputParseException(Component.text(e.getMessage()));
        }
        String[] inputs = PLAIN.matcher(from).matches() ? new String[]{from} : from.split("[|,]");
        Extent extent = context.extent();
        Map<Block, Optional<BlockState>> swaps = new ConcurrentHashMap<>();
        java.util.function.Function<BlockState, BlockState> swap = existing -> {
            Optional<BlockState> target = swaps.computeIfAbsent(existing.getBlock(), block -> {
                String oldId = BuiltInRegistries.BLOCK.getKey(block).getPath();
                String newId = oldId;
                if (compiled != null) {
                    newId = compiled.matcher(oldId).replaceAll(to);
                } else {
                    for (String input : inputs) {
                        newId = newId.replace(input, to);
                    }
                }
                if (newId.equals(oldId)) {
                    return Optional.empty();
                }
                Identifier id = Identifier.tryParse(newId);
                Optional<Block> found = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
                return found.map(Block::defaultBlockState);
            });
            return target.map(state -> copyProperties(existing, state)).orElse(null);
        };
        return new Pattern() {
            @Override
            public BlockState state(int x, int y, int z) {
                BlockState existing = extent.get(x, y, z);
                BlockState swapped = swap.apply(existing);
                return swapped == null ? existing : swapped;
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                BlockState swapped = swap.apply(extent.get(x, y, z));
                return swapped != null && dest.set(x, y, z, swapped);
            }
        };
    }

    private static Pattern hotbar(List<String> args, ParseContext context) throws InputParseException {
        if (!context.actor().isPlayer()) {
            throw InputParseException.of("kawe.error.parser.player-only", "#hotbar");
        }
        List<Block> blocks = context.actor().hotbarBlocks();
        if (blocks.isEmpty()) {
            throw InputParseException.of("kawe.error.no-valid-on-hotbar");
        }
        Map<Pattern, Double> weights = new LinkedHashMap<>();
        for (Block block : blocks) {
            weights.merge(Pattern.of(block.defaultBlockState()), 1d, Double::sum);
        }
        return new Weighted(weights, TRUE_RANDOM);
    }

    private static Pattern clipboard(List<String> args, ParseContext context) throws InputParseException {
        ClipboardHolder holder = context.session().clipboard();
        if (holder == null) {
            throw InputParseException.of("kawe.error.empty-clipboard");
        }
        int[] offset = {0, 0, 0};
        if (!args.isEmpty()) {
            String[] parts = args.getFirst().split(",");
            if (parts.length != 3) {
                throw InputParseException.of("kawe.error.parser.clipboard.missing-coordinates");
            }
            for (int i = 0; i < 3; i++) {
                try {
                    offset[i] = Integer.parseInt(parts[i].trim());
                } catch (NumberFormatException e) {
                    throw InputParseException.of("kawe.error.invalid-number.matches", parts[i]);
                }
            }
        }
        Clipboard source = holder.clipboard();
        BlockVec min = source.min();
        BlockVec size = source.dimensions();
        return new Pattern() {
            private int at(int value, int shift, int length, int base) {
                return (int) (Math.floorMod((long) value + shift, (long) length) + base);
            }

            @Override
            public BlockState state(int x, int y, int z) {
                return source.get(at(x, offset[0], size.x(), min.x()), at(y, offset[1], size.y(), min.y()), at(z, offset[2], size.z(), min.z()));
            }

            @Override
            public CompoundTag tile(int x, int y, int z) {
                return source.tile(at(x, offset[0], size.x(), min.x()), at(y, offset[1], size.y(), min.y()), at(z, offset[2], size.z(), min.z()));
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                return dest.set(x, y, z, state(x, y, z), tile(x, y, z));
            }
        };
    }

    private static Pattern fullCopy(List<String> args, ParseContext context) throws InputParseException {
        if (args.isEmpty() || args.size() > 3) {
            throw InputParseException.of("kawe.error.command.syntax", "#fullcopy[source][rotate][flip] (e.g. #fullcopy[#copy][true][false])");
        }
        boolean rotate = args.size() >= 2 && Boolean.parseBoolean(args.get(1).trim());
        boolean flip = args.size() == 3 && Boolean.parseBoolean(args.get(2).trim());
        String source = args.getFirst().trim().toLowerCase(Locale.ROOT);
        List<Clipboard> clipboards;
        Transform base;
        if ("#copy".startsWith(source) || "#clipboard".startsWith(source)) {
            ClipboardHolder holder = context.session() == null ? null : context.session().clipboard();
            if (holder == null) {
                throw InputParseException.of("kawe.error.parse.no-clipboard", "#fullcopy");
            }
            clipboards = holder.clipboards();
            base = holder.base();
        } else {
            try {
                clipboards = Schematics.loadAll(args.getFirst().trim(), Settings.get().loadAllFiles());
            } catch (IOException e) {
                throw new InputParseException(Component.text(String.valueOf(e.getMessage())));
            }
            base = Transform.IDENTITY;
        }
        if (clipboards.isEmpty()) {
            throw InputParseException.of("kawe.error.parse.no-clipboard-source", args.getFirst());
        }
        return new Pattern() {
            @Override
            public BlockState state(int x, int y, int z) {
                return null;
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                if (!(dest instanceof Edit edit)) {
                    throw new IllegalStateException("#fullcopy only pastes into world edits");
                }
                ThreadLocalRandom random = ThreadLocalRandom.current();
                Clipboard clipboard = clipboards.get(random.nextInt(clipboards.size()));
                Transform transform = base;
                if (rotate) {
                    transform = Transform.IDENTITY.rotateY(random.nextInt(4) * 90).combine(transform);
                }
                if (flip && random.nextBoolean()) {
                    transform = Transform.IDENTITY.scale(new Vec3(-1, 1, 1)).combine(transform);
                }
                return ClipboardOps.paste(edit, transform, clipboard, new BlockVec(x, y, z), false, false, null) > 0;
            }
        };
    }

    public static Pattern existing(Extent extent) {
        return extent::get;
    }

    private static Pattern randomState(String input, ParseContext context) throws InputParseException {
        BlockInput.Parsed parsed = BlockInput.parse(input, context);
        List<BlockState> states = new ArrayList<>();
        for (BlockState state : parsed.state().getBlock().getStateDefinition().getPossibleStates()) {
            if (matchesExplicit(state, parsed.properties())) {
                states.add(state);
            }
        }
        BlockState[] options = states.toArray(BlockState[]::new);
        return (x, y, z) -> options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static boolean matchesExplicit(BlockState state, Map<Property<?>, Comparable<?>> explicit) {
        for (Map.Entry<Property<?>, Comparable<?>> entry : explicit.entrySet()) {
            if (!state.getValue(entry.getKey()).equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Pattern tag(String name) throws InputParseException {
        List<Block> blocks = BlockMatcher.tagBlocks(name);
        BlockState[] options = blocks.stream().map(Block::defaultBlockState).toArray(BlockState[]::new);
        return (x, y, z) -> options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private static Pattern applying(String input, ParseContext context) throws InputParseException {
        Extent extent = context.extent();
        String type = input;
        String states = null;
        int open = input.indexOf('[');
        if (open >= 0) {
            if (!input.endsWith("]")) {
                throw InputParseException.of("kawe.error.parser.missing-rbracket");
            }
            type = input.substring(0, open);
            states = input.substring(open + 1, input.length() - 1);
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (states != null) {
            for (String part : states.split(",")) {
                if (part.isEmpty()) {
                    throw InputParseException.of("kawe.error.parser.empty-state");
                }
                String[] kv = part.split("=", 2);
                if (kv.length != 2) {
                    throw InputParseException.of("kawe.error.parser.missing-equals-separator");
                }
                if (kv[0].isEmpty()) {
                    throw InputParseException.of("kawe.error.parser.empty-property");
                }
                if (kv[1].isEmpty()) {
                    throw InputParseException.of("kawe.error.parser.empty-value");
                }
                if (values.put(kv[0].toLowerCase(Locale.ROOT), kv[1].toLowerCase(Locale.ROOT)) != null) {
                    throw InputParseException.of("kawe.error.parser.duplicate-property", kv[0]);
                }
            }
        }
        BlockState target = type.isEmpty() ? null : BlockInput.state(type, context).getBlock().defaultBlockState();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (target != null) {
                Property<?> property = target.getBlock().getStateDefinition().getProperty(entry.getKey());
                if (property == null) {
                    throw InputParseException.of("kawe.error.parser.unknown-property", entry.getKey(), type);
                }
                if (property.getValue(entry.getValue()).isEmpty()) {
                    throw InputParseException.of("kawe.error.parser.unknown-value", entry.getValue(), entry.getKey());
                }
            } else if (!Masks.PropertyNames.ALL.contains(entry.getKey())) {
                throw InputParseException.of("kawe.error.parser.unknown-property", entry.getKey(), "^");
            } else if (!Masks.PropertyNames.validValue(entry.getKey(), entry.getValue())) {
                throw InputParseException.of("kawe.error.parser.unknown-value", entry.getValue(), entry.getKey());
            }
        }
        return (x, y, z) -> {
            BlockState current = extent.get(x, y, z);
            BlockState next = target == null ? current : copyProperties(current, target);
            return values.isEmpty() ? next : withValues(next, values);
        };
    }

    public static BlockState copyProperties(BlockState from, BlockState to) {
        BlockState out = to;
        for (Property<?> property : from.getProperties()) {
            Property<?> match = to.getBlock().getStateDefinition().getProperty(property.getName());
            if (match != null) {
                out = withValue(out, match, BlockMatcher.valueName(from, property));
            }
        }
        return out;
    }

    public static BlockState withValues(BlockState state, Map<String, String> values) {
        BlockState out = state;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property != null) {
                out = withValue(out, property, entry.getValue());
            }
        }
        return out;
    }

    public static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() && state.hasProperty(property) ? state.setValue(property, parsed.get()) : state;
    }
}
