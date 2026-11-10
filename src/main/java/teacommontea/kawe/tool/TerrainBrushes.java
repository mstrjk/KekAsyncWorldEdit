package teacommontea.kawe.tool;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.color.BlockColors;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.op.RegionOps;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.CuboidRegion;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class TerrainBrushes {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private TerrainBrushes() {
    }

    public static Brush blendBall(int minFreqDiff, boolean onlyAir, String maskInput) {
        return u -> {
            Edit edit = u.edit();
            Mask mask = maskInput == null ? null : Masks.parse(maskInput, u.context().parseContext(edit));
            BlockVec center = u.target();
            int reach = (int) (u.size() + 1);
            long limit = (long) (u.size() * u.size());
            for (int x = -reach; x <= reach; x++) {
                for (int y = -reach; y <= reach; y++) {
                    for (int z = -reach; z <= reach; z++) {
                        if ((long) x * x + (long) y * y + (long) z * z >= limit) {
                            continue;
                        }
                        int gx = center.x() + x;
                        int gy = center.y() + y;
                        int gz = center.z() + z;
                        if (gy < edit.minY() || gy > edit.maxY() || (mask != null && !mask.test(gx, gy, gz))) {
                            continue;
                        }
                        blend(edit, mask, gx, gy, gz, minFreqDiff, onlyAir);
                    }
                }
            }
        };
    }

    private static void blend(Edit edit, Mask mask, int gx, int gy, int gz, int minFreqDiff, boolean onlyAir) {
        BlockState current = edit.original(gx, gy, gz);
        Map<Block, Integer> frequency = new HashMap<>();
        Map<Block, BlockState> sample = new HashMap<>();
        int highest = 1;
        int currentFrequency = 1;
        BlockState highestState = current;
        int air = 0;
        int total = 26;
        boolean tie = false;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                for (int oy = -1; oy <= 1; oy++) {
                    if (ox == 0 && oy == 0 && oz == 0) {
                        continue;
                    }
                    int y = gy + oy;
                    if (y < edit.minY() || y > edit.maxY()) {
                        total--;
                        continue;
                    }
                    BlockState state = mask != null && !mask.test(gx + ox, y, gz + oz) ? AIR : edit.original(gx + ox, y, gz + oz);
                    if (state.isAir()) {
                        air++;
                    }
                    Block block = state.getBlock();
                    sample.putIfAbsent(block, state);
                    int count = frequency.merge(block, 1, Integer::sum);
                    if (block == current.getBlock()) {
                        currentFrequency++;
                    }
                    if (count - highest >= minFreqDiff) {
                        highest = count;
                        highestState = sample.get(block);
                        tie = false;
                    } else if (count == highest) {
                        tie = true;
                    }
                }
            }
        }
        if (onlyAir) {
            if (air * 2 - total >= minFreqDiff) {
                if (!current.isAir()) {
                    edit.set(gx, gy, gz, AIR);
                }
            } else if (current.isAir() && total - 2 * air >= minFreqDiff) {
                edit.set(gx, gy, gz, highestState);
            }
            return;
        }
        if (highest - currentFrequency >= minFreqDiff && !tie && current != highestState) {
            edit.set(gx, gy, gz, highestState);
        }
    }

    public static Brush morph(int erodeFaces, int erodeIterations, int fillFaces, int fillIterations) {
        return u -> {
            Edit edit = u.edit();
            BlockVec center = u.target();
            int size = (int) u.size();
            long limit = (long) (u.size() * u.size());
            int span = size * 2 + 3;
            int offset = size + 1;
            BlockState[] read = new BlockState[span * span * span];
            for (int x = 0; x < span; x++) {
                for (int y = 0; y < span; y++) {
                    for (int z = 0; z < span; z++) {
                        int wy = center.y() + y - offset;
                        read[(x * span + y) * span + z] = wy < edit.minY() || wy > edit.maxY() ? AIR : edit.original(center.x() + x - offset, wy, center.z() + z - offset);
                    }
                }
            }
            BlockState[] original = read.clone();
            BlockState[] write = read.clone();
            for (int i = 0; i < erodeIterations + fillIterations; i++) {
                boolean erode = i < erodeIterations;
                int faces = erode ? erodeFaces : fillFaces;
                System.arraycopy(read, 0, write, 0, read.length);
                for (int x = 1; x < span - 1; x++) {
                    for (int y = 1; y < span - 1; y++) {
                        for (int z = 1; z < span - 1; z++) {
                            int dx = x - offset;
                            int dy = y - offset;
                            int dz = z - offset;
                            if ((long) dx * dx + (long) dy * dy + (long) dz * dz >= limit) {
                                continue;
                            }
                            BlockState state = read[(x * span + y) * span + z];
                            if (Masks.blocksMovement(state) != erode) {
                                continue;
                            }
                            Map<Block, Integer> counts = new HashMap<>();
                            BlockState best = null;
                            int bestCount = 0;
                            int total = 0;
                            for (int[] face : FACES) {
                                BlockState next = read[((x + face[0]) * span + y + face[1]) * span + z + face[2]];
                                if (Masks.blocksMovement(next) == erode) {
                                    continue;
                                }
                                total++;
                                int count = counts.merge(next.getBlock(), 1, Integer::sum);
                                if (count > bestCount || (!erode && count == bestCount)) {
                                    bestCount = count;
                                    best = next;
                                }
                            }
                            if (best != null && total >= faces) {
                                write[(x * span + y) * span + z] = best;
                            }
                        }
                    }
                }
                BlockState[] swap = read;
                read = write;
                write = swap;
            }
            for (int x = 1; x < span - 1; x++) {
                for (int y = 1; y < span - 1; y++) {
                    for (int z = 1; z < span - 1; z++) {
                        int index = (x * span + y) * span + z;
                        if (read[index] != original[index]) {
                            edit.set(center.x() + x - offset, center.y() + y - offset, center.z() + z - offset, read[index]);
                        }
                    }
                }
            }
        };
    }

    public interface HeightSource {
        double height(int x, int z, int size);
    }

    public static HeightSource cone() {
        return (x, z, size) -> {
            long d2 = (long) x * x + (long) z * z;
            return d2 > (long) size * size ? 0 : Math.max(0, size - Math.sqrt(d2));
        };
    }

    public static HeightSource cylinder() {
        return (x, z, size) -> (long) x * x + (long) z * z > (long) size * size ? 0 : size;
    }

    public static HeightSource image(BufferedImage image) {
        int width = image.getWidth();
        int length = image.getHeight();
        char[][] data = new char[width][length];
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < length; z++) {
                int pixel = image.getRGB(x, z);
                int alpha = pixel >>> 24;
                int grey = ((pixel >> 16 & 0xFF) + (pixel >> 8 & 0xFF) + (pixel & 0xFF)) / 3;
                data[x][z] = (char) (alpha * grey / 255);
            }
        }
        return array(data, 256);
    }

    public static HeightSource clipboard(Clipboard clipboard, int worldHeight) {
        BlockVec min = clipboard.min();
        BlockVec dimensions = clipboard.dimensions();
        char[][] data = new char[dimensions.x()][dimensions.z()];
        int height = dimensions.y();
        for (int x = 0; x < dimensions.x(); x++) {
            for (int z = 0; z < dimensions.z(); z++) {
                int top = 0;
                for (int y = 0; y < height; y++) {
                    if (!clipboard.get(min.x() + x, min.y() + y, min.z() + z).isAir()) {
                        top = y + 1;
                    }
                }
                data[x][z] = (char) ((long) worldHeight * top / height);
            }
        }
        return array(data, worldHeight);
    }

    private static HeightSource array(char[][] data, double scale) {
        int width = data.length;
        int length = data[0].length;
        return (x, z, size) -> {
            double rx = (double) width / (size * 2);
            double rz = (double) length / (size * 2);
            int ix = (int) Math.clamp((x + size) * rx, 0, width - 1);
            int iz = (int) Math.clamp((z + size) * rz, 0, length - 1);
            return data[ix][iz] * size / scale;
        };
    }

    static double sample(HeightSource source, int x, int z, int size, int rotation, double randomAngle) {
        int rx = x;
        int rz = z;
        if (randomAngle != 0) {
            double cos = Math.cos(randomAngle);
            double sin = Math.sin(randomAngle);
            rx = (int) Math.round(x * cos - z * sin);
            rz = (int) Math.round(x * sin + z * cos);
        }
        return switch (rotation) {
            case 1 -> source.height(rz, rx, size);
            case 2 -> source.height(-rx, -rz, size);
            case 3 -> source.height(-rz, -rx, size);
            default -> source.height(rx, rz, size);
        };
    }

    public static Brush height(HeightSource source, int rotationDegrees, double yscale, boolean smooth, boolean towards, boolean randomRotate, boolean layers) {
        int rotation = (rotationDegrees / 90) % 4;
        return u -> {
            Edit edit = u.edit();
            BlockVec pos = u.target();
            int size = (int) u.size();
            int diameter = size * 2 + 1;
            double angle = randomRotate ? Math.toRadians(ThreadLocalRandom.current().nextInt(360)) : 0;
            CuboidRegion region = new CuboidRegion(new BlockVec(pos.x() - size, Math.max(edit.minY(), pos.y() - size), pos.z() - size),
                    new BlockVec(pos.x() + size, edit.maxY(), pos.z() + size));
            float[] current = new float[diameter * diameter];
            float[] target = new float[diameter * diameter];
            int bottom = region.min().y();
            int range = edit.maxY() - edit.minY() + 1;
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int previous = pos.y();
            double sizePowInv = 1d / Math.pow(Math.max(1, size), yscale);
            for (int x = -size; x <= size; x++) {
                for (int z = -size; z <= size; z++) {
                    int index = (z + size) * diameter + (x + size);
                    int surface = Masks.nearestSurface(edit, pos.x() + x, pos.z() + z, previous);
                    if (surface == Integer.MIN_VALUE || surface < bottom) {
                        current[index] = bottom;
                        target[index] = bottom;
                        continue;
                    }
                    previous = surface;
                    double raise = sample(source, x, z, size, rotation, angle);
                    float height = surface;
                    if (layers) {
                        BlockState top = edit.get(pos.x() + x, surface, pos.z() + z);
                        height = top.getBlock() == Blocks.SNOW ? surface + (top.getValue(SnowLayerBlock.LAYERS) - 1) / 8f : surface + 1;
                    }
                    current[index] = height;
                    double change;
                    if (towards) {
                        double diff = (layers ? pos.y() + 1 : pos.y()) - height;
                        change = diff * (Math.pow(raise, yscale) * sizePowInv);
                    } else {
                        change = yscale * raise;
                    }
                    if (layers) {
                        target[index] = (float) Math.clamp(Math.round((height + change) * 8) / 8.0, edit.minY() + 1, edit.maxY() + 0.875);
                        continue;
                    }
                    double fraction = Math.abs(change - (long) change);
                    int extra = random.nextInt(range) < (int) (fraction * range) ? (change >= 0 ? 1 : -1) : 0;
                    target[index] = (float) Math.clamp(surface + (long) change + extra, edit.minY(), edit.maxY());
                }
            }
            if (smooth) {
                float[] blurred = RegionOps.gaussianBlur(target, diameter, diameter);
                for (int i = 0; i < blurred.length; i++) {
                    target[i] = layers ? Math.round(blurred[i] * 8) / 8f : Math.round(blurred[i]);
                }
            }
            if (layers) {
                RegionOps.applySnowHeights(edit, region, current, target, 0);
            } else {
                RegionOps.applyHeights(edit, region, current, target);
            }
        };
    }

    public static Brush stencil(HeightSource source, int rotationDegrees, double yscale, boolean onlyWhite, boolean randomRotate) {
        int rotation = (rotationDegrees / 90) % 4;
        return u -> {
            Edit edit = u.edit();
            Pattern pattern = u.pattern();
            BlockVec center = u.target();
            int size = (int) u.size();
            long size2 = (long) (u.size() * u.size());
            int range = edit.maxY() - edit.minY() + 1;
            int add = yscale < 0 ? range - 1 : 0;
            int cutoff = onlyWhite ? range - 1 : 0;
            double scale = yscale / Math.max(1, u.size()) * range;
            double angle = randomRotate ? Math.toRadians(ThreadLocalRandom.current().nextInt(360)) : 0;
            double yaw = Math.toRadians(-u.context().actor().yaw());
            double pitch = Math.toRadians(u.context().actor().pitch() - 90);
            double[][] view = inverse(rotationY(yaw), rotationX(pitch));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Set<BlockVec> seen = new HashSet<>();
            ArrayDeque<BlockVec> queue = new ArrayDeque<>();
            queue.add(center);
            seen.add(center);
            while (!queue.isEmpty()) {
                BlockVec at = queue.poll();
                if (!Brushes.inWorld(edit, at.y()) || !Masks.solid(edit.get(at.x(), at.y(), at.z()))) {
                    continue;
                }
                int dx = at.x() - center.x();
                int dy = at.y() - center.y();
                int dz = at.z() - center.z();
                int sx = (int) Math.round(view[0][0] * dx + view[0][1] * dy + view[0][2] * dz);
                int sz = (int) Math.round(view[2][0] * dx + view[2][1] * dy + view[2][2] * dz);
                if ((long) sx * sx + (long) sz * sz > size2 || Math.abs(sx) > 256 || Math.abs(sz) > 256) {
                    continue;
                }
                int value = (int) Math.ceil(sample(source, sx, sz, size, rotation, angle) * scale) + add;
                if (value >= cutoff && (value >= 255 || random.nextInt(range) < value)) {
                    pattern.apply(edit, at.x(), at.y(), at.z());
                }
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oy = -1; oy <= 1; oy++) {
                        for (int oz = -1; oz <= 1; oz++) {
                            BlockVec next = at.add(ox, oy, oz);
                            if (seen.add(next)) {
                                queue.add(next);
                            }
                        }
                    }
                }
            }
        };
    }

    public static Brush image(BufferedImage image, boolean alpha) {
        int width = image.getWidth();
        int height = image.getHeight();
        long[][] sums = new long[4][(width + 1) * (height + 1)];
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int color = image.getRGB(x, z);
                int[] parts = {color >>> 24, color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF};
                for (int c = 0; c < 4; c++) {
                    int index = (z + 1) * (width + 1) + x + 1;
                    sums[c][index] = parts[c] + sums[c][index - 1] + sums[c][index - width - 1] - sums[c][index - width - 2];
                }
            }
        }
        return u -> {
            Edit edit = u.edit();
            BlockVec center = u.target();
            double scale = Math.max(width, height) / Math.max(1, u.size());
            double yaw = Math.toRadians(-u.context().actor().yaw());
            double pitch = Math.toRadians(u.context().actor().pitch() - 90);
            double[][] view = inverse(rotationY(yaw), rotationX(pitch));
            BlockColors.Palette palette = u.context().session().palette();
            for (BlockVec at : Brushes.surfaceArea(edit, center, u.size())) {
                int dx = at.x() - center.x();
                int dy = at.y() - center.y();
                int dz = at.z() - center.z();
                double sx = (view[0][0] * dx + view[0][1] * dy + view[0][2] * dz) * scale + width / 2.0;
                double sz = (view[2][0] * dx + view[2][1] * dy + view[2][2] * dz) * scale + height / 2.0;
                int x1 = (int) Math.floor(sx - scale / 2);
                int z1 = (int) Math.floor(sz - scale / 2);
                int x2 = (int) Math.ceil(sx + scale / 2);
                int z2 = (int) Math.ceil(sz + scale / 2);
                x1 = Math.clamp(x1, 0, width);
                z1 = Math.clamp(z1, 0, height);
                x2 = Math.clamp(x2, 0, width);
                z2 = Math.clamp(z2, 0, height);
                if (x2 <= x1 || z2 <= z1) {
                    continue;
                }
                long area = (long) (x2 - x1) * (z2 - z1);
                int[] average = new int[4];
                for (int c = 0; c < 4; c++) {
                    long sum = sums[c][z2 * (width + 1) + x2] - sums[c][z1 * (width + 1) + x2] - sums[c][z2 * (width + 1) + x1] + sums[c][z1 * (width + 1) + x1];
                    average[c] = (int) (sum / area);
                }
                int color = average[0] << 24 | average[1] << 16 | average[2] << 8 | average[3];
                if (alpha) {
                    if (average[0] == 0) {
                        continue;
                    }
                    if (average[0] < 255) {
                        int existing = BlockColors.colorOf(palette, edit.get(at.x(), at.y(), at.z()), edit.biome(at.x(), at.y(), at.z()), at.x(), at.z());
                        color = BlockColors.overlay(color, existing == 0 ? 0xFF000000 : existing);
                    }
                } else {
                    color |= 0xFF000000;
                }
                Block block = palette.nearest(color);
                if (block != null) {
                    edit.set(at.x(), at.y(), at.z(), block.defaultBlockState());
                }
            }
        };
    }

    private static double[][] rotationY(double angle) {
        return new double[][]{{Math.cos(angle), 0, Math.sin(angle)}, {0, 1, 0}, {-Math.sin(angle), 0, Math.cos(angle)}};
    }

    private static double[][] rotationX(double angle) {
        return new double[][]{{1, 0, 0}, {0, Math.cos(angle), -Math.sin(angle)}, {0, Math.sin(angle), Math.cos(angle)}};
    }

    private static double[][] inverse(double[][] first, double[][] second) {
        double[][] product = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    product[i][j] += second[i][k] * first[k][j];
                }
            }
        }
        double[][] transposed = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                transposed[i][j] = product[j][i];
            }
        }
        return transposed;
    }
}
