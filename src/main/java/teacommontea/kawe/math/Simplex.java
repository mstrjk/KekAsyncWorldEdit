package teacommontea.kawe.math;

public final class Simplex {

    private static final int[][] GRAD = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};
    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
    private static final double F3 = 1.0 / 3.0;
    private static final double G3 = 1.0 / 6.0;

    private final long seed;

    public Simplex(long seed) {
        this.seed = seed;
    }

    private static long floor(double x) {
        long xi = (long) x;
        return x < xi ? xi - 1 : xi;
    }

    private int gradient(long i, long j, long k) {
        long h = seed ^ (i * 0x9E3779B97F4A7C15L) ^ (j * 0xC2B2AE3D27D4EB4FL) ^ (k * 0x165667B19E3779F9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (int) ((h >>> 1) % 12);
    }

    private double corner2(long i, long j, double x, double y) {
        double t = 0.5 - x * x - y * y;
        if (t < 0) {
            return 0.0;
        }
        t *= t;
        int[] g = GRAD[gradient(i, j, 0)];
        return t * t * (g[0] * x + g[1] * y);
    }

    private double corner3(long i, long j, long k, double x, double y, double z) {
        double t = 0.6 - x * x - y * y - z * z;
        if (t < 0) {
            return 0.0;
        }
        t *= t;
        int[] g = GRAD[gradient(i, j, k)];
        return t * t * (g[0] * x + g[1] * y + g[2] * z);
    }

    public double noise(double xin, double yin) {
        double s = (xin + yin) * F2;
        long i = floor(xin + s);
        long j = floor(yin + s);
        double t = (i + j) * G2;
        double x0 = xin - (i - t);
        double y0 = yin - (j - t);
        int i1 = x0 > y0 ? 1 : 0;
        int j1 = x0 > y0 ? 0 : 1;
        double n0 = corner2(i, j, x0, y0);
        double n1 = corner2(i + i1, j + j1, x0 - i1 + G2, y0 - j1 + G2);
        double n2 = corner2(i + 1, j + 1, x0 - 1.0 + 2.0 * G2, y0 - 1.0 + 2.0 * G2);
        return 70.0 * (n0 + n1 + n2);
    }

    public double noise(double xin, double yin, double zin) {
        double s = (xin + yin + zin) * F3;
        long i = floor(xin + s);
        long j = floor(yin + s);
        long k = floor(zin + s);
        double t = (i + j + k) * G3;
        double x0 = xin - (i - t);
        double y0 = yin - (j - t);
        double z0 = zin - (k - t);
        int i1;
        int j1;
        int k1;
        int i2;
        int j2;
        int k2;
        if (x0 >= y0) {
            i1 = y0 >= z0 || x0 >= z0 ? 1 : 0;
            j1 = 0;
            k1 = 1 - i1;
            i2 = 1;
            j2 = y0 >= z0 ? 1 : 0;
            k2 = 1 - j2;
        } else {
            i1 = 0;
            j1 = y0 < z0 ? 0 : 1;
            k1 = 1 - j1;
            i2 = y0 < z0 || x0 < z0 ? 0 : 1;
            j2 = 1;
            k2 = 1 - i2;
        }
        double n0 = corner3(i, j, k, x0, y0, z0);
        double n1 = corner3(i + i1, j + j1, k + k1, x0 - i1 + G3, y0 - j1 + G3, z0 - k1 + G3);
        double n2 = corner3(i + i2, j + j2, k + k2, x0 - i2 + 2.0 * G3, y0 - j2 + 2.0 * G3, z0 - k2 + 2.0 * G3);
        double n3 = corner3(i + 1, j + 1, k + 1, x0 - 1.0 + 3.0 * G3, y0 - 1.0 + 3.0 * G3, z0 - 1.0 + 3.0 * G3);
        return 32.0 * (n0 + n1 + n2 + n3);
    }

    public double fractal(double x, double y, double z, int octaves, double lacunarity, double persistence) {
        double sum = 0;
        double amplitude = 1;
        double frequency = 1;
        double total = 0;
        for (int octave = 0; octave < Math.max(1, octaves); octave++) {
            sum += amplitude * noise(x * frequency, y * frequency, z * frequency);
            total += amplitude;
            amplitude *= persistence;
            frequency *= lacunarity;
        }
        return sum / total;
    }
}
