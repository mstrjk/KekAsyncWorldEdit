package teacommontea.kawe.math;

public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE = new Vec3(1, 1, 1);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 multiply(double m) {
        return new Vec3(x * m, y * m, z * m);
    }

    public Vec3 multiply(Vec3 o) {
        return new Vec3(x * o.x, y * o.y, z * o.z);
    }

    public Vec3 divide(double d) {
        return new Vec3(x / d, y / d, z / d);
    }

    public Vec3 divide(Vec3 o) {
        return new Vec3(x / o.x, y / o.y, z / o.z);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public Vec3 normalize() {
        double l = length();
        return l == 0 ? this : divide(l);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double distance(Vec3 o) {
        return subtract(o).length();
    }

    public Vec3 abs() {
        return new Vec3(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    public Vec3 min(Vec3 o) {
        return new Vec3(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public Vec3 max(Vec3 o) {
        return new Vec3(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    public BlockVec toBlock() {
        return BlockVec.at(x, y, z);
    }

    public BlockVec round() {
        return new BlockVec((int) Math.round(x), (int) Math.round(y), (int) Math.round(z));
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
