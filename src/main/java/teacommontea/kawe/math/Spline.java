package teacommontea.kawe.math;

import java.util.List;

public final class Spline {

    private static final int ARC_SAMPLES = 8;

    private final Vec3[] nodes;
    private final Vec3[] a;
    private final Vec3[] b;
    private final Vec3[] c;
    private final Vec3[] d;
    private final double scaling;

    public Spline(List<Vec3> points, double tension, double bias, double continuity) {
        this.nodes = points.toArray(Vec3[]::new);
        int count = nodes.length;
        a = new Vec3[count];
        b = new Vec3[count];
        c = new Vec3[count];
        d = new Vec3[count];
        double ta = (1 - tension) * (1 + bias) * (1 + continuity) / 2;
        double tb = (1 - tension) * (1 - bias) * (1 - continuity) / 2;
        double tc = (1 - tension) * (1 + bias) * (1 - continuity) / 2;
        double td = (1 - tension) * (1 - bias) * (1 + continuity) / 2;
        for (int i = 0; i < count; i++) {
            a[i] = combine(i, -ta, ta - tb - tc + 2, tb + tc - td - 2, td);
            b[i] = combine(i, 2 * ta, -2 * ta + 2 * tb + tc - 3, -2 * tb - tc + td + 3, -td);
            c[i] = combine(i, -ta, ta - tb, tb, 0);
            d[i] = node(i);
        }
        scaling = count - 1;
    }

    private Vec3 node(int index) {
        return nodes[Math.clamp(index, 0, nodes.length - 1)];
    }

    private Vec3 combine(int base, double f1, double f2, double f3, double f4) {
        return node(base - 1).multiply(f1).add(node(base).multiply(f2)).add(node(base + 1).multiply(f3)).add(node(base + 2).multiply(f4));
    }

    public Vec3 position(double t) {
        double scaled = Math.clamp(t, 0, 1) * scaling;
        int index = Math.min((int) Math.floor(scaled), nodes.length - 1);
        double r = scaled - index;
        double r2 = r * r;
        double r3 = r2 * r;
        return new Vec3(a[index].x() * r3 + b[index].x() * r2 + c[index].x() * r + d[index].x(),
                a[index].y() * r3 + b[index].y() * r2 + c[index].y() * r + d[index].y(),
                a[index].z() * r3 + b[index].z() * r2 + c[index].z() * r + d[index].z());
    }

    public double length() {
        double total = 0;
        for (int i = 0; i < nodes.length - 1; i++) {
            total += segmentLength(i, 0, 1);
        }
        return total;
    }

    private double segmentLength(int index, double from, double to) {
        Vec3 a3 = a[index].multiply(3.0);
        Vec3 b2 = b[index].multiply(2.0);
        double sum = speed(a3, b2, c[index], from) / 2.0 + speed(a3, b2, c[index], to) / 2.0;
        for (int i = 1; i < ARC_SAMPLES; i++) {
            sum += speed(a3, b2, c[index], from + (to - from) * i / ARC_SAMPLES);
        }
        return sum * (to - from) / ARC_SAMPLES;
    }

    private static double speed(Vec3 a3, Vec3 b2, Vec3 c, double t) {
        return a3.multiply(t).add(b2).multiply(t).add(c).length();
    }
}
