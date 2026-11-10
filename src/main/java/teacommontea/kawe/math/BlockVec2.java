package teacommontea.kawe.math;

public record BlockVec2(int x, int z) {

    public BlockVec2 add(int dx, int dz) {
        return new BlockVec2(x + dx, z + dz);
    }

    public BlockVec toBlock(int y) {
        return new BlockVec(x, y, z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ")";
    }
}
