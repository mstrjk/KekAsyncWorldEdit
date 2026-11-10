package teacommontea.kawe.parse;

import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.clipboard.BlockTransform;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.engine.WriteTransform;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class Transforms {

    private Transforms() {
    }

    public static WriteTransform parse(String input, ParseContext context) throws InputParseException {
        if (input == null || input.isBlank()) {
            return null;
        }
        List<Input.Entry> entries = Input.split(input);
        List<List<WriteTransform>> groups = new ArrayList<>();
        List<List<Double>> weights = new ArrayList<>();
        groups.add(new ArrayList<>());
        weights.add(new ArrayList<>());
        for (Input.Entry entry : entries) {
            if (entry.and()) {
                groups.add(new ArrayList<>());
                weights.add(new ArrayList<>());
            }
            String full = entry.full();
            double chance = 1;
            int percent = full.indexOf('%');
            if (percent > 0 && full.substring(0, percent).matches("[0-9]+(\\.[0-9]*)?")) {
                chance = Double.parseDouble(full.substring(0, percent));
                full = full.substring(percent + 1);
            }
            groups.getLast().add(single(full, context));
            weights.getLast().add(chance);
        }
        List<WriteTransform> chain = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            chain.add(groups.get(i).size() == 1 ? groups.get(i).getFirst() : random(groups.get(i), weights.get(i)));
        }
        return chain.size() == 1 ? chain.getFirst() : all(chain);
    }

    private static WriteTransform single(String full, ParseContext context) throws InputParseException {
        List<Input.Entry> parsed = Input.split(full);
        if (parsed.size() != 1) {
            return parse(full, context);
        }
        Input.Entry entry = parsed.getFirst();
        String command = entry.command().toLowerCase(Locale.ROOT);
        List<String> args = entry.args();
        return switch (command) {
            case "#offset" -> offset(args, context);
            case "#spread", "#randomoffset" -> spread(args, context);
            case "#scale" -> scale(args, context);
            case "#rotate" -> rotate(args, context);
            case "#pattern" -> pattern(args, context);
            case "#linear" -> linear(args, context, false);
            case "#linear3d" -> linear(args, context, true);
            default -> {
                if (command.isEmpty() && !args.isEmpty()) {
                    yield parse(String.join(",", args), context);
                }
                throw InputParseException.of("kawe.error.parse.unknown-transform", full, "#offset #spread #scale #rotate #pattern #linear #linear3d");
            }
        };
    }

    private static int intArg(List<String> args, int index, String usage) throws InputParseException {
        try {
            return Integer.parseInt(args.get(index).trim());
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
    }

    private static double doubleArg(List<String> args, int index, String usage) throws InputParseException {
        try {
            double value = Double.parseDouble(args.get(index).trim());
            if (!Double.isFinite(value)) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
    }

    private static WriteTransform next(List<String> args, int index, ParseContext context) throws InputParseException {
        return args.size() > index ? parse(args.get(index), context) : null;
    }

    private static boolean forward(WriteTransform next, WriteTransform.Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
        return next == null ? out.set(x, y, z, state, tile) : next.write(out, x, y, z, state, tile);
    }

    private static boolean forwardBiome(WriteTransform next, WriteTransform.Writer out, int x, int y, int z, Holder<Biome> biome) {
        return next == null ? out.setBiome(x, y, z, biome) : next.writeBiome(out, x, y, z, biome);
    }

    private static WriteTransform offset(List<String> args, ParseContext context) throws InputParseException {
        String usage = "#offset[x][y][z]";
        if (args.size() != 3 && args.size() != 4) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        int dx = intArg(args, 0, usage);
        int dy = intArg(args, 1, usage);
        int dz = intArg(args, 2, usage);
        WriteTransform next = next(args, 3, context);
        return new WriteTransform() {
            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                return forward(next, out, x + dx, y + dy, z + dz, state, tile);
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                return forwardBiome(next, out, x + dx, y + dy, z + dz, biome);
            }
        };
    }

    private static WriteTransform spread(List<String> args, ParseContext context) throws InputParseException {
        String usage = "#spread[x][y][z]";
        if (args.size() != 3 && args.size() != 4) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        int dx = intArg(args, 0, usage);
        int dy = intArg(args, 1, usage);
        int dz = intArg(args, 2, usage);
        if (dx < 0 || dy < 0 || dz < 0 || dx > 1 << 20 || dy > 1 << 20 || dz > 1 << 20) {
            throw InputParseException.of("kawe.error.outside-range", "offset", "0", String.valueOf(1 << 20));
        }
        WriteTransform next = next(args, 3, context);
        return new WriteTransform() {
            private int jitter(int value, int range) {
                return value + ThreadLocalRandom.current().nextInt(range * 2 + 1) - range;
            }

            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                return forward(next, out, jitter(x, dx), jitter(y, dy), jitter(z, dz), state, tile);
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                return forwardBiome(next, out, jitter(x, dx), jitter(y, dy), jitter(z, dz), biome);
            }
        };
    }

    private static WriteTransform scale(List<String> args, ParseContext context) throws InputParseException {
        String usage = "#scale[x][y][z]";
        double sx;
        double sy;
        double sz;
        WriteTransform next;
        if (args.size() == 1) {
            sx = sy = sz = doubleArg(args, 0, usage);
            next = null;
        } else if (args.size() == 3 || args.size() == 4) {
            sx = doubleArg(args, 0, usage);
            sy = doubleArg(args, 1, usage);
            sz = doubleArg(args, 2, usage);
            next = next(args, 3, context);
        } else {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        if (sx <= 0 || sy <= 0 || sz <= 0 || sx > 64 || sy > 64 || sz > 64) {
            throw InputParseException.of("kawe.error.outside-range", "scale", "0", "64");
        }
        AtomicReference<BlockVec> origin = new AtomicReference<>(context.anchor());
        return new WriteTransform() {
            private BlockVec origin(int x, int y, int z) {
                BlockVec current = origin.get();
                if (current == null) {
                    origin.compareAndSet(null, new BlockVec(x, y, z));
                    current = origin.get();
                }
                return current;
            }

            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                BlockVec o = origin(x, y, z);
                double fx = o.x() + (x - o.x()) * sx;
                double fy = o.y() + (y - o.y()) * sy;
                double fz = o.z() + (z - o.z()) * sz;
                boolean changed = false;
                for (int ty = (int) Math.floor(fy); ty < fy + sy; ty++) {
                    for (int tz = (int) Math.floor(fz); tz < fz + sz; tz++) {
                        for (int tx = (int) Math.floor(fx); tx < fx + sx; tx++) {
                            changed |= forward(next, out, tx, ty, tz, state, tile);
                        }
                    }
                }
                return changed;
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                BlockVec o = origin(x, y, z);
                return forwardBiome(next, out, (int) Math.floor(o.x() + (x - o.x()) * sx), (int) Math.floor(o.y() + (y - o.y()) * sy),
                        (int) Math.floor(o.z() + (z - o.z()) * sz), biome);
            }
        };
    }

    private static WriteTransform rotate(List<String> args, ParseContext context) throws InputParseException {
        String usage = "#rotate[x][y][z]";
        Transform rotation;
        WriteTransform next;
        if (args.size() == 1) {
            rotation = Transform.IDENTITY.rotateY(doubleArg(args, 0, usage));
            next = null;
        } else if (args.size() == 3 || args.size() == 4) {
            rotation = Transform.IDENTITY.rotateX(doubleArg(args, 0, usage)).rotateY(doubleArg(args, 1, usage)).rotateZ(doubleArg(args, 2, usage));
            next = next(args, 3, context);
        } else {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        BlockTransform blocks = new BlockTransform(rotation);
        return new WriteTransform() {
            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                return forward(next, out, x, y, z, blocks.apply(state), tile);
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                return forwardBiome(next, out, x, y, z, biome);
            }
        };
    }

    private static WriteTransform pattern(List<String> args, ParseContext context) throws InputParseException {
        if (args.isEmpty() || args.size() > 2) {
            throw InputParseException.of("kawe.error.command.syntax", "#pattern[pattern]");
        }
        Pattern pattern = Patterns.parse(args.getFirst(), context);
        WriteTransform next = next(args, 1, context);
        return new WriteTransform() {
            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                Pattern picked = pattern.pick(x, y, z);
                BlockState replacement = picked.state(x, y, z);
                return replacement != null && forward(next, out, x, y, z, replacement, picked.tile(x, y, z));
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                return forwardBiome(next, out, x, y, z, biome);
            }
        };
    }

    private static List<WriteTransform> members(List<String> args, ParseContext context, String usage) throws InputParseException {
        if (args.size() != 1) {
            throw InputParseException.of("kawe.error.command.syntax", usage);
        }
        List<WriteTransform> out = new ArrayList<>();
        for (Input.Entry entry : Input.split(args.getFirst())) {
            out.add(single(entry.full(), context));
        }
        return out;
    }

    private static WriteTransform linear(List<String> args, ParseContext context, boolean threeD) throws InputParseException {
        List<WriteTransform> options = members(args, context, threeD ? "#linear3d[transforms]" : "#linear[transforms]");
        AtomicInteger counter = new AtomicInteger();
        return new WriteTransform() {
            private WriteTransform pick(int x, int y, int z) {
                int index = threeD ? Math.floorMod((long) x + y + z, options.size()) : Math.floorMod(counter.getAndIncrement(), options.size());
                return options.get(index);
            }

            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                return pick(x, y, z).write(out, x, y, z, state, tile);
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                return pick(x, y, z).writeBiome(out, x, y, z, biome);
            }
        };
    }

    private static WriteTransform random(List<WriteTransform> options, List<Double> weights) throws InputParseException {
        double[] cumulative = new double[options.size()];
        double total = 0;
        for (int i = 0; i < options.size(); i++) {
            total += Math.max(0, weights.get(i));
            cumulative[i] = total;
        }
        if (total <= 0) {
            throw new InputParseException(Component.text("Transform weights must add up to more than 0"));
        }
        double sum = total;
        return new WriteTransform() {
            private WriteTransform pick() {
                double roll = ThreadLocalRandom.current().nextDouble(sum);
                for (int i = 0; i < cumulative.length; i++) {
                    if (roll < cumulative[i]) {
                        return options.get(i);
                    }
                }
                return options.getLast();
            }

            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                return pick().write(out, x, y, z, state, tile);
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                return pick().writeBiome(out, x, y, z, biome);
            }
        };
    }

    private static WriteTransform all(List<WriteTransform> chain) {
        return new WriteTransform() {
            @Override
            public boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile) {
                boolean changed = false;
                for (WriteTransform transform : chain) {
                    changed |= transform.write(out, x, y, z, state, tile);
                }
                return changed;
            }

            @Override
            public boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome) {
                boolean changed = false;
                for (WriteTransform transform : chain) {
                    changed |= transform.writeBiome(out, x, y, z, biome);
                }
                return changed;
            }
        };
    }
}
