package teacommontea.kawe.parse;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.color.BlockColors;
import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.function.Pattern;

import java.util.List;
import java.util.function.IntUnaryOperator;

final class ColorPatterns {

    private ColorPatterns() {
    }

    static void register() {
        Patterns.register((args, c) -> fixed(c, color(args, "#color")), "#color", "#colour");
        Patterns.register((args, c) -> {
            int color = color(args, "#averagecolor");
            return recolor(c, current -> BlockColors.average(current, color));
        }, "#averagecolor", "#averagecolour");
        Patterns.register((args, c) -> {
            int color = color(args, "#saturate");
            return recolor(c, current -> BlockColors.multiply(current, color));
        }, "#saturate");
        Patterns.register(ColorPatterns::desaturate, "#desaturate");
        Patterns.register((args, c) -> shade(c, true), "#darken");
        Patterns.register((args, c) -> shade(c, false), "#lighten");
        Patterns.register(ColorPatterns::angle, "#anglecolor", "#anglecolour");
    }

    private static int color(List<String> args, String name) throws InputParseException {
        if (args.size() != 4) {
            throw InputParseException.of("kawe.error.command.syntax", name + "[r][g][b][a] (e.g. " + name + "[156][100][0][120])");
        }
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                values[i] = Integer.parseInt(args.get(i).trim());
            } catch (NumberFormatException e) {
                throw InputParseException.of("kawe.error.invalid-number.matches", args.get(i));
            }
        }
        return BlockColors.argb(values[0], values[1], values[2], values[3]);
    }

    private static BlockColors.Palette palette(ParseContext context) {
        return context.session() == null ? BlockColors.all() : context.session().palette();
    }

    private static Pattern fixed(ParseContext context, int color) throws InputParseException {
        Block block = palette(context).nearest(color);
        if (block == null) {
            throw InputParseException.of("kawe.error.no-block-found", Integer.toHexString(color));
        }
        return Pattern.of(block.defaultBlockState());
    }

    private interface Replace {
        Block pick(BlockColors.Palette palette, int current, int wanted);
    }

    private static Pattern replacing(ParseContext context, IntUnaryOperator change, Replace pick) {
        Extent extent = context.extent();
        BlockColors.Palette palette = palette(context);
        return new Pattern() {
            private BlockState next(int x, int y, int z) {
                BlockState existing = extent.get(x, y, z);
                int current = BlockColors.colorOf(palette, existing, extent.biome(x, y, z), x, z);
                if (current == 0) {
                    return null;
                }
                int wanted = change.applyAsInt(current);
                Block block = pick.pick(palette, current, wanted);
                return block == null || block == existing.getBlock() ? null : block.defaultBlockState();
            }

            @Override
            public BlockState state(int x, int y, int z) {
                BlockState next = next(x, y, z);
                return next == null ? extent.get(x, y, z) : next;
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                BlockState next = next(x, y, z);
                return next != null && dest.set(x, y, z, next);
            }
        };
    }

    private static Pattern recolor(ParseContext context, IntUnaryOperator change) {
        return replacing(context, change, (palette, current, wanted) -> palette.nearest(wanted));
    }

    private static Pattern desaturate(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() != 1) {
            throw InputParseException.of("kawe.error.command.syntax", "#desaturate[percent] (e.g. #desaturate[90])");
        }
        double value;
        try {
            value = Math.clamp(Double.parseDouble(args.getFirst().trim()) / 100, 0, 1);
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", args.getFirst());
        }
        return replacing(context, color -> {
            int r = color >> 16 & 0xFF;
            int g = color >> 8 & 0xFF;
            int b = color & 0xFF;
            double luminance = 0.3 * r + 0.6 * g + 0.1 * b;
            return BlockColors.argb((int) (r + value * (luminance - r)), (int) (g + value * (luminance - g)), (int) (b + value * (luminance - b)),
                    color >>> 24);
        }, (palette, current, wanted) -> wanted == current ? null : palette.nextNearest(wanted));
    }

    private static Pattern shade(ParseContext context, boolean darker) {
        return replacing(context, color -> color, (palette, current, wanted) -> darker ? palette.darker(current) : palette.lighter(current));
    }

    private static Pattern angle(List<String> args, ParseContext context) throws InputParseException {
        if (args.size() != 1) {
            throw InputParseException.of("kawe.error.command.syntax", "#anglecolor[distance] (e.g. #anglecolor[10])");
        }
        int distance;
        try {
            distance = Integer.parseInt(args.getFirst().trim());
        } catch (NumberFormatException e) {
            throw InputParseException.of("kawe.error.invalid-number.matches", args.getFirst());
        }
        if (distance < 1) {
            throw InputParseException.of("kawe.error.outside-range-lower", "distance", "1");
        }
        Extent extent = context.extent();
        BlockColors.Palette palette = palette(context);
        double factor = 1d / distance / (extent.maxY() - extent.minY() + 1);
        return new Pattern() {
            private BlockState next(int x, int y, int z) {
                BlockState existing = extent.get(x, y, z);
                if (!Masks.blocksMovement(existing)) {
                    return null;
                }
                int slope = slope(extent, x, y, z, distance);
                int color = BlockColors.colorOf(palette, existing, extent.biome(x, y, z), x, z);
                if (color == 0) {
                    return null;
                }
                double scale = slope == Integer.MAX_VALUE ? 0 : 1 - Math.min(1, slope * factor);
                int shaded = BlockColors.argb((int) ((color >> 16 & 0xFF) * scale), (int) ((color >> 8 & 0xFF) * scale), (int) ((color & 0xFF) * scale),
                        color >>> 24);
                Block block = palette.nearest(shaded);
                return block == null || block == existing.getBlock() ? null : block.defaultBlockState();
            }

            @Override
            public BlockState state(int x, int y, int z) {
                BlockState next = next(x, y, z);
                return next == null ? extent.get(x, y, z) : next;
            }

            @Override
            public boolean apply(Extent dest, int x, int y, int z) {
                BlockState next = next(x, y, z);
                return next != null && dest.set(x, y, z, next);
            }
        };
    }

    private static int surface(Extent extent, int x, int z, int y) {
        int at = Masks.nearestSurface(extent, x, z, y);
        return at == Integer.MIN_VALUE ? extent.minY() : at;
    }

    private static int slope(Extent extent, int x, int y, int z, int d) {
        int height = surface(extent, x, z, y);
        if (height > extent.minY() && !Masks.blocksMovement(extent.get(x, height - 1, z))) {
            return Integer.MAX_VALUE;
        }
        int slope = Math.abs(surface(extent, x + d, z, y) - surface(extent, x - d, z, y)) * 7;
        slope += Math.abs(surface(extent, x, z + d, y) - surface(extent, x, z - d, y)) * 7;
        slope += Math.abs(surface(extent, x + d, z + d, y) - surface(extent, x - d, z - d, y)) * 5;
        slope += Math.abs(surface(extent, x - d, z + d, y) - surface(extent, x + d, z - d, y)) * 5;
        return slope;
    }
}
