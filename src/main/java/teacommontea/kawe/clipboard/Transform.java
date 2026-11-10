package teacommontea.kawe.clipboard;

import teacommontea.kawe.math.Vec3;

public record Transform(double m00, double m01, double m02, double m03,
                        double m10, double m11, double m12, double m13,
                        double m20, double m21, double m22, double m23) {

    public static final Transform IDENTITY = new Transform(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);

    private static final double SINGULAR = 1e-12;

    public boolean isIdentity() {
        return isApproximatelyIdentity(0);
    }

    public boolean isApproximatelyIdentity(double epsilon) {
        return near(m00, 1, epsilon) && near(m01, 0, epsilon) && near(m02, 0, epsilon) && near(m03, 0, epsilon)
                && near(m10, 0, epsilon) && near(m11, 1, epsilon) && near(m12, 0, epsilon) && near(m13, 0, epsilon)
                && near(m20, 0, epsilon) && near(m21, 0, epsilon) && near(m22, 1, epsilon) && near(m23, 0, epsilon);
    }

    private static boolean near(double value, double target, double epsilon) {
        return Math.abs(value - target) <= epsilon;
    }

    private static double cos(double degrees) {
        double mod = ((degrees % 360) + 360) % 360;
        if (mod == 0) {
            return 1;
        }
        if (mod == 90 || mod == 270) {
            return 0;
        }
        if (mod == 180) {
            return -1;
        }
        return Math.cos(Math.toRadians(degrees));
    }

    private static double sin(double degrees) {
        double mod = ((degrees % 360) + 360) % 360;
        if (mod == 0 || mod == 180) {
            return 0;
        }
        if (mod == 90) {
            return 1;
        }
        if (mod == 270) {
            return -1;
        }
        return Math.sin(Math.toRadians(degrees));
    }

    public Transform rotateX(double theta) {
        double c = cos(theta);
        double s = sin(theta);
        return concatenate(new Transform(1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0));
    }

    public Transform rotateY(double theta) {
        double c = cos(theta);
        double s = sin(theta);
        return concatenate(new Transform(c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0));
    }

    public Transform rotateZ(double theta) {
        double c = cos(theta);
        double s = sin(theta);
        return concatenate(new Transform(c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0));
    }

    public Transform scale(Vec3 v) {
        return concatenate(new Transform(v.x(), 0, 0, 0, 0, v.y(), 0, 0, 0, 0, v.z(), 0));
    }

    public Transform translate(Vec3 v) {
        return concatenate(new Transform(1, 0, 0, v.x(), 0, 1, 0, v.y(), 0, 0, 1, v.z()));
    }

    public Transform concatenate(Transform that) {
        return new Transform(
                m00 * that.m00 + m01 * that.m10 + m02 * that.m20,
                m00 * that.m01 + m01 * that.m11 + m02 * that.m21,
                m00 * that.m02 + m01 * that.m12 + m02 * that.m22,
                m00 * that.m03 + m01 * that.m13 + m02 * that.m23 + m03,
                m10 * that.m00 + m11 * that.m10 + m12 * that.m20,
                m10 * that.m01 + m11 * that.m11 + m12 * that.m21,
                m10 * that.m02 + m11 * that.m12 + m12 * that.m22,
                m10 * that.m03 + m11 * that.m13 + m12 * that.m23 + m13,
                m20 * that.m00 + m21 * that.m10 + m22 * that.m20,
                m20 * that.m01 + m21 * that.m11 + m22 * that.m21,
                m20 * that.m02 + m21 * that.m12 + m22 * that.m22,
                m20 * that.m03 + m21 * that.m13 + m22 * that.m23 + m23);
    }

    public Transform combine(Transform other) {
        return concatenate(other);
    }

    public double determinant() {
        return m00 * (m11 * m22 - m12 * m21) - m01 * (m10 * m22 - m12 * m20) + m02 * (m10 * m21 - m11 * m20);
    }

    public Transform inverse() {
        double det = determinant();
        if (!Double.isFinite(det) || Math.abs(det) < SINGULAR) {
            throw new ArithmeticException("Transform is not invertible (determinant " + det + ")");
        }
        double i00 = (m11 * m22 - m21 * m12) / det;
        double i01 = (m02 * m21 - m01 * m22) / det;
        double i02 = (m01 * m12 - m02 * m11) / det;
        double i10 = (m12 * m20 - m10 * m22) / det;
        double i11 = (m00 * m22 - m02 * m20) / det;
        double i12 = (m10 * m02 - m00 * m12) / det;
        double i20 = (m10 * m21 - m20 * m11) / det;
        double i21 = (m20 * m01 - m00 * m21) / det;
        double i22 = (m00 * m11 - m10 * m01) / det;
        return new Transform(
                i00, i01, i02, -(i00 * m03 + i01 * m13 + i02 * m23),
                i10, i11, i12, -(i10 * m03 + i11 * m13 + i12 * m23),
                i20, i21, i22, -(i20 * m03 + i21 * m13 + i22 * m23));
    }

    public Vec3 apply(Vec3 v) {
        return new Vec3(
                v.x() * m00 + v.y() * m01 + v.z() * m02 + m03,
                v.x() * m10 + v.y() * m11 + v.z() * m12 + m13,
                v.x() * m20 + v.y() * m21 + v.z() * m22 + m23);
    }

    public Vec3 applyDirection(Vec3 v) {
        return new Vec3(
                v.x() * m00 + v.y() * m01 + v.z() * m02,
                v.x() * m10 + v.y() * m11 + v.z() * m12,
                v.x() * m20 + v.y() * m21 + v.z() * m22);
    }
}
