package teacommontea.kawe.schematic;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.color.BlockColors;
import teacommontea.kawe.math.BlockVec;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.ImageIO;

final class Isometric {

    private static final int SIZE = 1080;

    private Isometric() {
    }

    static void write(Clipboard clipboard, OutputStream out) throws IOException {
        BlockVec min = clipboard.min();
        BlockVec size = clipboard.dimensions();
        int width = size.x();
        int height = size.y();
        int length = size.z();
        double d = Math.min((SIZE - 2) / (double) (width + length), (SIZE - 2) / ((width + length) / 2.0 + height));
        double half = d / 2;
        double left = (SIZE - (width + length) * d) / 2 + width * d;
        double top = (SIZE - ((width + length) * half + height * d)) / 2 + height * d;
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.drawRect(0, 0, SIZE - 1, SIZE - 1);
        boolean fill = length * 4 < SIZE && width * 4 < SIZE;
        BlockColors.Palette palette = BlockColors.all();
        Holder<Biome> plains = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        int[] topX = new int[4];
        int[] topY = new int[4];
        int[] rightX = new int[4];
        int[] rightY = new int[4];
        int[] leftX = new int[4];
        int[] leftY = new int[4];
        for (int xx = 0; xx < width; xx++) {
            int x = min.x() + xx;
            for (int zz = 0; zz < length; zz++) {
                int z = min.z() + zz;
                double cx = left + (zz - xx) * d;
                double base = top + (zz + xx) * half;
                for (int yy = 0; yy < height; yy++) {
                    int y = min.y() + yy;
                    BlockState state = clipboard.get(x, y, z);
                    if (state.isAir()) {
                        continue;
                    }
                    Holder<Biome> biome = clipboard.biome(x, y, z);
                    if (biome == null) {
                        biome = plains;
                    }
                    int color = BlockColors.colorOf(palette, state, biome, x, z);
                    if (color == 0) {
                        continue;
                    }
                    if (!clipboard.get(x, y + 1, z).isAir() && !clipboard.get(x, y, z + 1).isAir() && !clipboard.get(x + 1, y, z).isAir()) {
                        continue;
                    }
                    double cy = base - yy * d;
                    int px = (int) cx;
                    int py = (int) cy;
                    quad(topX, topY, px, py, (int) (cx - d), (int) (cy - half), px, (int) (cy - d), (int) (cx + d), (int) (cy - half));
                    quad(rightX, rightY, px, py, (int) (cx + d), (int) (cy - half), (int) (cx + d), (int) (cy + half), px, (int) (cy + d));
                    quad(leftX, leftY, px, py, (int) (cx - d), (int) (cy - half), (int) (cx - d), (int) (cy + half), px, (int) (cy + d));
                    Color face = new Color(color, false);
                    paint(g, fill, face, topX, topY);
                    paint(g, fill, shade(face, 0.8), rightX, rightY);
                    paint(g, fill, shade(face, 0.65), leftX, leftY);
                }
            }
        }
        g.dispose();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("No PNG writer available");
        }
    }

    private static void quad(int[] xs, int[] ys, int x0, int y0, int x1, int y1, int x2, int y2, int x3, int y3) {
        xs[0] = x0;
        ys[0] = y0;
        xs[1] = x1;
        ys[1] = y1;
        xs[2] = x2;
        ys[2] = y2;
        xs[3] = x3;
        ys[3] = y3;
    }

    private static void paint(Graphics2D g, boolean fill, Color color, int[] xs, int[] ys) {
        g.setColor(color);
        if (fill) {
            g.fillPolygon(xs, ys, 4);
        } else {
            g.drawPolygon(xs, ys, 4);
        }
    }

    private static Color shade(Color color, double factor) {
        return new Color((int) (color.getRed() * factor), (int) (color.getGreen() * factor), (int) (color.getBlue() * factor));
    }
}
