package teacommontea.kawe.color;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import javax.imageio.ImageIO;

public final class BlockColors {

    private static final int[] FACTORS = new int[766];
    private static final Palette ALL;

    static {
        for (int i = 1; i < FACTORS.length; i++) {
            FACTORS[i] = 65535 / i;
        }
        GrassColor.init(colormap());
        ALL = load();
    }

    private static int[] colormap() {
        try (InputStream raw = BlockColors.class.getResourceAsStream("/data/grass_colormap.png")) {
            if (raw == null) {
                throw new IOException("data/grass_colormap.png missing from jar");
            }
            BufferedImage image = ImageIO.read(raw);
            if (image == null || image.getWidth() != 256 || image.getHeight() != 256) {
                throw new IOException("data/grass_colormap.png is not a 256x256 image");
            }
            return image.getRGB(0, 0, 256, 256, null, 0, 256);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BlockColors() {
    }

    public record Entry(Block block, int color, long variance) {
    }

    public static final class Palette {
        private final Entry[] entries;
        private final Map<Block, Integer> colors = new HashMap<>();
        private final boolean random;

        Palette(List<Entry> list) {
            this(list, false);
        }

        Palette(List<Entry> list, boolean random) {
            this.random = random;
            this.entries = list.toArray(Entry[]::new);
            for (Entry entry : entries) {
                colors.put(entry.block(), entry.color());
            }
        }

        public boolean isEmpty() {
            return entries.length == 0;
        }

        public Palette filter(Predicate<Entry> keep) {
            return new Palette(Arrays.stream(entries).filter(keep).toList(), random);
        }

        public Palette randomized(boolean value) {
            return new Palette(Arrays.asList(entries), value);
        }

        public Palette varianceRange(double lowPercent, double highPercent) {
            if (entries.length == 0) {
                return this;
            }
            long[] sorted = Arrays.stream(entries).mapToLong(Entry::variance).sorted().toArray();
            long low = sorted[(int) Math.min(sorted.length - 1, Math.max(0, Math.floor(lowPercent / 100 * (sorted.length - 1))))];
            long high = sorted[(int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(highPercent / 100 * (sorted.length - 1))))];
            return filter(entry -> entry.variance() >= low && entry.variance() <= high);
        }

        public int color(Block block) {
            return colors.getOrDefault(block, 0);
        }

        public Block nearest(int color) {
            return random ? dithered(color) : nearest(color, entry -> true);
        }

        public Block nextNearest(int color) {
            return nearest(color, entry -> entry.color() != color);
        }

        public Block darker(int color) {
            int intensity = intensity(color);
            return nearest(color, entry -> entry.color() != color && intensity(entry.color()) < intensity);
        }

        public Block lighter(int color) {
            int intensity = intensity(color);
            return nearest(color, entry -> entry.color() != color && intensity(entry.color()) > intensity);
        }

        public Block nearest(int color, Predicate<Entry> allowed) {
            int red = color >> 16 & 0xFF;
            int green = color >> 8 & 0xFF;
            int blue = color & 0xFF;
            int alpha = color >>> 24;
            long best = Long.MAX_VALUE;
            Block found = null;
            for (Entry entry : entries) {
                if (entry.color() >>> 24 != alpha || !allowed.test(entry)) {
                    continue;
                }
                long distance = distance(red, green, blue, entry.color());
                if (distance < best) {
                    best = distance;
                    found = entry.block();
                }
            }
            return found;
        }

        public Block dithered(int color) {
            int red = color >> 16 & 0xFF;
            int green = color >> 8 & 0xFF;
            int blue = color & 0xFF;
            int alpha = color >>> 24;
            long best = Long.MAX_VALUE;
            for (Entry entry : entries) {
                if (entry.color() >>> 24 == alpha) {
                    best = Math.min(best, distance(red, green, blue, entry.color()));
                }
            }
            if (best == Long.MAX_VALUE) {
                return null;
            }
            List<Block> close = within(color, Math.max(best * 2, best + 64));
            return close.isEmpty() ? nearest(color, entry -> true) : close.get(ThreadLocalRandom.current().nextInt(close.size()));
        }

        public List<Block> within(int color, long limit) {
            int red = color >> 16 & 0xFF;
            int green = color >> 8 & 0xFF;
            int blue = color & 0xFF;
            int alpha = color >>> 24;
            List<Block> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.color() >>> 24 == alpha && distance(red, green, blue, entry.color()) <= limit) {
                    out.add(entry.block());
                }
            }
            return out;
        }
    }

    public static Palette all() {
        return ALL;
    }

    public static int colorOf(Palette palette, BlockState state, Holder<Biome> biome, int x, int z) {
        Block block = state.getBlock();
        int color = palette.color(block);
        if (color == 0) {
            color = ALL.color(block);
        }
        if (block == Blocks.GRASS_BLOCK && biome != null && color != 0) {
            return multiply(color, 0xFF000000 | biome.value().getGrassColor(x, z));
        }
        return color;
    }

    private static Palette load() {
        List<Entry> entries = new ArrayList<>();
        try (InputStream raw = BlockColors.class.getResourceAsStream("/data/block_colors.tsv")) {
            if (raw == null) {
                throw new IOException("data/block_colors.tsv missing from jar");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length != 3) {
                    continue;
                }
                Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(parts[0])).orElse(null);
                if (block == null || !usable(block)) {
                    continue;
                }
                entries.add(new Entry(block, (int) Long.parseLong(parts[1], 16), Long.parseLong(parts[2])));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Palette(entries);
    }

    private static boolean usable(Block block) {
        BlockState state = block.defaultBlockState();
        String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) && !state.hasBlockEntity() && !name.contains("shulker")
                && block != Blocks.SLIME_BLOCK && block != Blocks.HONEY_BLOCK;
    }

    public static int overlay(int top, int below) {
        int alpha = top >>> 24;
        int inverse = 255 - alpha;
        int red = ((top >> 16 & 0xFF) * alpha + (below >> 16 & 0xFF) * inverse) / 255;
        int green = ((top >> 8 & 0xFF) * alpha + (below >> 8 & 0xFF) * inverse) / 255;
        int blue = ((top & 0xFF) * alpha + (below & 0xFF) * inverse) / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    public static int intensity(int color) {
        return 2 * (color >> 16 & 0xFF) + 4 * (color >> 8 & 0xFF) + 3 * (color & 0xFF);
    }

    public static int average(int a, int b) {
        return ((a >>> 24) + (b >>> 24)) >> 1 << 24 | ((a >> 16 & 0xFF) + (b >> 16 & 0xFF)) >> 1 << 16
                | ((a >> 8 & 0xFF) + (b >> 8 & 0xFF)) >> 1 << 8 | ((a & 0xFF) + (b & 0xFF)) >> 1;
    }

    public static int multiply(int a, int b) {
        return (a >>> 24) * (b >>> 24) / 255 << 24 | (a >> 16 & 0xFF) * (b >> 16 & 0xFF) / 255 << 16
                | (a >> 8 & 0xFF) * (b >> 8 & 0xFF) / 255 << 8 | (a & 0xFF) * (b & 0xFF) / 255;
    }

    public static int argb(int red, int green, int blue, int alpha) {
        return Math.clamp(alpha, 0, 255) << 24 | Math.clamp(red, 0, 255) << 16 | Math.clamp(green, 0, 255) << 8 | Math.clamp(blue, 0, 255);
    }

    public static long distance(int red1, int green1, int blue1, int c2) {
        int red2 = c2 >> 16 & 0xFF;
        int green2 = c2 >> 8 & 0xFF;
        int blue2 = c2 & 0xFF;
        int mean = (red1 + red2) >> 1;
        int r = red1 - red2;
        int g = green1 - green2;
        int b = blue1 - blue2;
        int hue = hueDistance(red1, green1, blue1, red2, green2, blue2);
        return (((long) (512 + mean) * r * r) >> 8) + 4L * g * g + (((long) (767 - mean) * b * b) >> 8) + (long) hue * hue;
    }

    private static int hueDistance(int red1, int green1, int blue1, int red2, int green2, int blue2) {
        int total1 = red1 + green1 + blue1;
        int total2 = red2 + green2 + blue2;
        if (total1 == 0 || total2 == 0) {
            return 0;
        }
        long r = (512 * ((long) red1 * FACTORS[total1] - (long) red2 * FACTORS[total2])) >> 10;
        long g = (long) green1 * FACTORS[total1] - (long) green2 * FACTORS[total2];
        long b = (767 * ((long) blue1 * FACTORS[total1] - (long) blue2 * FACTORS[total2])) >> 10;
        return (int) ((r * r + g * g + b * b) >> 25);
    }
}
