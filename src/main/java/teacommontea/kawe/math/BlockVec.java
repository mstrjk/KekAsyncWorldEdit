package teacommontea.kawe.math;

public record BlockVec(int x, int y, int z) {

    public static final BlockVec ZERO = new BlockVec(0, 0, 0);
    public static final BlockVec ONE = new BlockVec(1, 1, 1);
    public static final BlockVec UNIT_X = new BlockVec(1, 0, 0);
    public static final BlockVec UNIT_Y = new BlockVec(0, 1, 0);
    public static final BlockVec UNIT_Z = new BlockVec(0, 0, 1);
    public static final BlockVec UNIT_MINUS_X = new BlockVec(-1, 0, 0);
    public static final BlockVec UNIT_MINUS_Y = new BlockVec(0, -1, 0);
    public static final BlockVec UNIT_MINUS_Z = new BlockVec(0, 0, -1);

    public static BlockVec at(double x, double y, double z) {
        return new BlockVec((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    public BlockVec add(int dx, int dy, int dz) {
        return new BlockVec(x + dx, y + dy, z + dz);
    }

    public BlockVec add(BlockVec o) {
        return new BlockVec(x + o.x, y + o.y, z + o.z);
    }

    public BlockVec subtract(BlockVec o) {
        return new BlockVec(x - o.x, y - o.y, z - o.z);
    }

    public BlockVec subtract(int dx, int dy, int dz) {
        return new BlockVec(x - dx, y - dy, z - dz);
    }

    public BlockVec multiply(int m) {
        return new BlockVec(x * m, y * m, z * m);
    }

    public BlockVec multiply(BlockVec o) {
        return new BlockVec(x * o.x, y * o.y, z * o.z);
    }

    public BlockVec divide(int d) {
        return new BlockVec(Math.floorDiv(x, d), Math.floorDiv(y, d), Math.floorDiv(z, d));
    }

    public BlockVec min(BlockVec o) {
        return new BlockVec(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public BlockVec max(BlockVec o) {
        return new BlockVec(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    public BlockVec abs() {
        return new BlockVec(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    public BlockVec withX(int v) {
        return new BlockVec(v, y, z);
    }

    public BlockVec withY(int v) {
        return new BlockVec(x, v, z);
    }

    public BlockVec withZ(int v) {
        return new BlockVec(x, y, v);
    }

    public BlockVec clampY(int min, int max) {
        return new BlockVec(x, Math.clamp(y, min, max), z);
    }

    public BlockVec negate() {
        return new BlockVec(-x, -y, -z);
    }

    public Vec3 toVec3() {
        return new Vec3(x, y, z);
    }

    public Vec3 center() {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    public long lengthSq() {
        return (long) x * x + (long) y * y + (long) z * z;
    }

    public double distance(BlockVec o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public long distanceSq(BlockVec o) {
        long dx = x - o.x;
        long dy = y - o.y;
        long dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public boolean containedWithin(BlockVec min, BlockVec max) {
        return x >= min.x && x <= max.x && y >= min.y && y <= max.y && z >= min.z && z <= max.z;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
