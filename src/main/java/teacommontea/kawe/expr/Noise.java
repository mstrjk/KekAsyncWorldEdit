package teacommontea.kawe.expr;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class Noise {

    private static final int X_NOISE_GEN = 1619;
    private static final int Y_NOISE_GEN = 31337;
    private static final int Z_NOISE_GEN = 6971;
    private static final int SEED_NOISE_GEN = 1013;
    private static final int SHIFT_NOISE_GEN = 8;
    private static final int MAX_OCTAVE = 30;
    private static final int VECTOR_TABLE = 1024;
    private static final double[] VECTORS = loadVectors();

    private Noise() {
    }

    private static double[] loadVectors() {
        try (InputStream raw = Noise.class.getResourceAsStream("/data/noise_vectors.bin")) {
            if (raw == null) {
                throw new IOException("data/noise_vectors.bin missing from jar");
            }
            DataInputStream in = new DataInputStream(raw);
            int length = in.readInt();
            if (length != VECTOR_TABLE) {
                throw new IOException("data/noise_vectors.bin holds " + length + " values, expected " + VECTOR_TABLE);
            }
            double[] out = new double[length];
            for (int i = 0; i < out.length; i++) {
                out[i] = in.readDouble();
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static double sCurve3(double a) {
        return a * a * (3.0 - 2.0 * a);
    }

    private static double lerp(double n0, double n1, double a) {
        return (1.0 - a) * n0 + a * n1;
    }

    private static int floor(double v) {
        return v > 0.0 ? (int) v : (int) v - 1;
    }

    private static int exactFloor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    static double makeInt32Range(double n) {
        if (n >= 1073741824.0) {
            return 2.0 * (n % 1073741824.0) - 1073741824.0;
        }
        if (n <= -1073741824.0) {
            return 2.0 * (n % 1073741824.0) + 1073741824.0;
        }
        return n;
    }

    static double gradient(double fx, double fy, double fz, int ix, int iy, int iz, int seed) {
        int index = X_NOISE_GEN * ix + Y_NOISE_GEN * iy + Z_NOISE_GEN * iz + SEED_NOISE_GEN * seed;
        index ^= index >> SHIFT_NOISE_GEN;
        index &= 0xff;
        double gx = VECTORS[index << 2];
        double gy = VECTORS[(index << 2) + 1];
        double gz = VECTORS[(index << 2) + 2];
        return (gx * (fx - ix) + gy * (fy - iy) + gz * (fz - iz)) * 2.12;
    }

    static double gradientCoherent(double x, double y, double z, int seed) {
        int x0 = floor(x);
        int x1 = x0 + 1;
        int y0 = floor(y);
        int y1 = y0 + 1;
        int z0 = floor(z);
        int z1 = z0 + 1;
        double xs = sCurve3(x - x0);
        double ys = sCurve3(y - y0);
        double zs = sCurve3(z - z0);
        double n0 = gradient(x, y, z, x0, y0, z0, seed);
        double n1 = gradient(x, y, z, x1, y0, z0, seed);
        double ix0 = lerp(n0, n1, xs);
        n0 = gradient(x, y, z, x0, y1, z0, seed);
        n1 = gradient(x, y, z, x1, y1, z0, seed);
        double ix1 = lerp(n0, n1, xs);
        double iy0 = lerp(ix0, ix1, ys);
        n0 = gradient(x, y, z, x0, y0, z1, seed);
        n1 = gradient(x, y, z, x1, y0, z1, seed);
        ix0 = lerp(n0, n1, xs);
        n0 = gradient(x, y, z, x0, y1, z1, seed);
        n1 = gradient(x, y, z, x1, y1, z1, seed);
        ix1 = lerp(n0, n1, xs);
        double iy1 = lerp(ix0, ix1, ys);
        return lerp(iy0, iy1, zs);
    }

    static int intValue(int x, int y, int z, int seed) {
        int n = (X_NOISE_GEN * x + Y_NOISE_GEN * y + Z_NOISE_GEN * z + SEED_NOISE_GEN * seed) & 0x7fffffff;
        n = (n >> 13) ^ n;
        return (n * (n * n * 60493 + 19990303) + 1376312589) & 0x7fffffff;
    }

    static double value(int x, int y, int z, int seed) {
        return 1.0 - intValue(x, y, z, seed) / 1073741824.0;
    }

    private static void checkOctaves(int octaves) {
        if (octaves < 1 || octaves > MAX_OCTAVE) {
            throw new IllegalArgumentException("octaveCount must be between 1 and MAX OCTAVE: " + MAX_OCTAVE);
        }
    }

    public static double perlin(int seed, double x, double y, double z, double frequency, int octaves, double persistence) {
        checkOctaves(octaves);
        double value = 0.0;
        double current = 1.0;
        double px = x * frequency;
        double py = y * frequency;
        double pz = z * frequency;
        for (int octave = 0; octave < octaves; octave++) {
            int octaveSeed = seed + octave;
            value += gradientCoherent(makeInt32Range(px), makeInt32Range(py), makeInt32Range(pz), octaveSeed) * current;
            px *= 2.0;
            py *= 2.0;
            pz *= 2.0;
            current *= persistence;
        }
        return range(value);
    }

    private static final double[] SPECTRAL = spectral();

    private static double[] spectral() {
        double[] weights = new double[MAX_OCTAVE];
        double frequency = 1.0;
        for (int i = 0; i < MAX_OCTAVE; i++) {
            weights[i] = Math.pow(frequency, -1.0);
            frequency *= 2.0;
        }
        return weights;
    }

    public static double ridged(int seed, double x, double y, double z, double frequency, int octaves) {
        checkOctaves(octaves);
        double px = x * frequency;
        double py = y * frequency;
        double pz = z * frequency;
        double value = 0.0;
        double weight = 1.0;
        for (int octave = 0; octave < octaves; octave++) {
            int octaveSeed = (seed + octave) & 0x7fffffff;
            double signal = gradientCoherent(makeInt32Range(px), makeInt32Range(py), makeInt32Range(pz), octaveSeed);
            signal = 1.0 - Math.abs(signal);
            signal *= signal;
            signal *= weight;
            weight = Math.clamp(signal * 2.0, 0.0, 1.0);
            value += signal * SPECTRAL[octave];
            px *= 2.0;
            py *= 2.0;
            pz *= 2.0;
        }
        return range(value * 1.25 - 1.0);
    }

    public static double voronoi(int seed, double x, double y, double z, double frequency) {
        double px = makeInt32Range(x * frequency);
        double py = makeInt32Range(y * frequency);
        double pz = makeInt32Range(z * frequency);
        long xi = floor(px);
        long yi = floor(py);
        long zi = floor(pz);
        double min = 2147483647.0;
        double cx = 0;
        double cy = 0;
        double cz = 0;
        for (long zl = zi - 2; zl <= zi + 2; zl++) {
            int zc = (int) zl;
            for (long yl = yi - 2; yl <= yi + 2; yl++) {
                int yc = (int) yl;
                for (long xl = xi - 2; xl <= xi + 2; xl++) {
                    int xc = (int) xl;
                    double xp = xc + value(xc, yc, zc, seed);
                    double yp = yc + value(xc, yc, zc, seed + 1);
                    double zp = zc + value(xc, yc, zc, seed + 2);
                    double dx = xp - px;
                    double dy = yp - py;
                    double dz = zp - pz;
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < min) {
                        min = distance;
                        cx = xp;
                        cy = yp;
                        cz = zp;
                    }
                }
            }
        }
        return range(value(exactFloor(cx), exactFloor(cy), exactFloor(cz), seed));
    }

    private static double range(double value) {
        return (float) Math.max(0, Math.min(1, value / 2.0 + 0.5));
    }
}
