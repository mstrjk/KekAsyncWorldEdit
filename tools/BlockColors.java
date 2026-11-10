import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class BlockColors {

    private static final String MODELS = "assets/minecraft/models/block/";
    private static final Pattern TEXTURES = Pattern.compile("\"textures\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern VALUE = Pattern.compile("\"[^\"]+\"\\s*:\\s*\"([^\"]+)\"");
    private static final int[] FACTORS = new int[766];

    static {
        for (int i = 1; i < FACTORS.length; i++) {
            FACTORS[i] = 65535 / i;
        }
    }

    private BlockColors() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java BlockColors.java <client.jar> <out.tsv>");
            System.exit(2);
        }
        TreeMap<String, String> rows = new TreeMap<>();
        try (ZipFile zip = new ZipFile(args[0])) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName();
                if (!path.startsWith(MODELS) || !path.endsWith(".json") || path.substring(MODELS.length()).contains("/")) {
                    continue;
                }
                String name = path.substring(MODELS.length(), path.length() - ".json".length());
                String texture = name.equals("grass_block") ? "block/grass_block_top" : singleTexture(read(zip, entry));
                if (texture == null) {
                    continue;
                }
                String file = "assets/minecraft/textures/" + (texture.contains(":") ? texture.substring(texture.indexOf(':') + 1) : texture) + ".png";
                ZipEntry image = zip.getEntry(file);
                if (image == null) {
                    continue;
                }
                BufferedImage pixels;
                try (InputStream in = zip.getInputStream(image)) {
                    pixels = ImageIO.read(in);
                }
                if (pixels == null) {
                    continue;
                }
                int color = average(pixels);
                rows.put(name, String.format("%s\t%08x\t%d", name, color, distance(pixels, color)));
            }
        }
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry colormap = zip.getEntry("assets/minecraft/textures/colormap/grass.png");
            if (colormap == null) {
                throw new IOException("grass colormap missing from " + args[0]);
            }
            try (InputStream in = zip.getInputStream(colormap)) {
                Files.write(Path.of(args[1]).resolveSibling("grass_colormap.png"), in.readAllBytes());
            }
        }
        List<String> lines = new ArrayList<>(rows.values());
        Files.write(Path.of(args[1]), lines, StandardCharsets.UTF_8);
        System.out.println("Wrote " + lines.size() + " block colors");
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String singleTexture(String json) {
        Matcher block = TEXTURES.matcher(json);
        if (!block.find()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        Matcher value = VALUE.matcher(block.group(1));
        while (value.find()) {
            values.add(value.group(1));
        }
        return values.size() == 1 && !values.iterator().next().startsWith("#") ? values.iterator().next() : null;
    }

    private static int average(BufferedImage image) {
        long red = 0;
        long green = 0;
        long blue = 0;
        long alpha = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int color = image.getRGB(x, y);
                red += color >> 16 & 0xFF;
                green += color >> 8 & 0xFF;
                blue += color & 0xFF;
                alpha += color >>> 24;
            }
        }
        long area = (long) width * height;
        return (int) (alpha / area) << 24 | (int) (red / area) << 16 | (int) (green / area) << 8 | (int) (blue / area);
    }

    private static long distance(BufferedImage image, int color) {
        long total = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        int red = color >> 16 & 0xFF;
        int green = color >> 8 & 0xFF;
        int blue = color & 0xFF;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                long d = colorDistance(red, green, blue, image.getRGB(x, y));
                total += d * d;
            }
        }
        return total / ((long) width * height);
    }

    static long colorDistance(int red1, int green1, int blue1, int c2) {
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

    static int hueDistance(int red1, int green1, int blue1, int red2, int green2, int blue2) {
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
