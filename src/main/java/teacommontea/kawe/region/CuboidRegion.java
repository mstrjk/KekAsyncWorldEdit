package teacommontea.kawe.region;

import teacommontea.kawe.math.BlockVec;

public final class CuboidRegion implements Region {

    private BlockVec pos1;
    private BlockVec pos2;
    private BlockVec min;
    private BlockVec max;

    public CuboidRegion(BlockVec pos1, BlockVec pos2) {
        this.pos1 = pos1;
        this.pos2 = pos2;
        recalculate();
    }

    private void recalculate() {
        min = pos1.min(pos2);
        max = pos1.max(pos2);
    }

    public BlockVec pos1() {
        return pos1;
    }

    public BlockVec pos2() {
        return pos2;
    }

    public void pos1(BlockVec v) {
        pos1 = v;
        recalculate();
    }

    public void pos2(BlockVec v) {
        pos2 = v;
        recalculate();
    }

    @Override
    public BlockVec min() {
        return min;
    }

    @Override
    public BlockVec max() {
        return max;
    }

    @Override
    public long volume() {
        return (long) width() * height() * length();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return x >= min.x() && x <= max.x() && y >= min.y() && y <= max.y() && z >= min.z() && z <= max.z();
    }

    @Override
    public boolean containsColumn(int x, int z) {
        return x >= min.x() && x <= max.x() && z >= min.z() && z <= max.z();
    }

    @Override
    public void expand(BlockVec... changes) {
        for (BlockVec change : changes) {
            if (change.x() > 0) {
                if (Math.max(pos1.x(), pos2.x()) == pos1.x()) {
                    pos1 = pos1.add(change.x(), 0, 0);
                } else {
                    pos2 = pos2.add(change.x(), 0, 0);
                }
            } else if (Math.min(pos1.x(), pos2.x()) == pos1.x()) {
                pos1 = pos1.add(change.x(), 0, 0);
            } else {
                pos2 = pos2.add(change.x(), 0, 0);
            }
            if (change.y() > 0) {
                if (Math.max(pos1.y(), pos2.y()) == pos1.y()) {
                    pos1 = pos1.add(0, change.y(), 0);
                } else {
                    pos2 = pos2.add(0, change.y(), 0);
                }
            } else if (Math.min(pos1.y(), pos2.y()) == pos1.y()) {
                pos1 = pos1.add(0, change.y(), 0);
            } else {
                pos2 = pos2.add(0, change.y(), 0);
            }
            if (change.z() > 0) {
                if (Math.max(pos1.z(), pos2.z()) == pos1.z()) {
                    pos1 = pos1.add(0, 0, change.z());
                } else {
                    pos2 = pos2.add(0, 0, change.z());
                }
            } else if (Math.min(pos1.z(), pos2.z()) == pos1.z()) {
                pos1 = pos1.add(0, 0, change.z());
            } else {
                pos2 = pos2.add(0, 0, change.z());
            }
        }
        recalculate();
    }

    @Override
    public void contract(BlockVec... changes) {
        for (BlockVec change : changes) {
            if (change.x() < 0) {
                if (Math.max(pos1.x(), pos2.x()) == pos1.x()) {
                    pos1 = pos1.add(change.x(), 0, 0);
                } else {
                    pos2 = pos2.add(change.x(), 0, 0);
                }
            } else if (Math.min(pos1.x(), pos2.x()) == pos1.x()) {
                pos1 = pos1.add(change.x(), 0, 0);
            } else {
                pos2 = pos2.add(change.x(), 0, 0);
            }
            if (change.y() < 0) {
                if (Math.max(pos1.y(), pos2.y()) == pos1.y()) {
                    pos1 = pos1.add(0, change.y(), 0);
                } else {
                    pos2 = pos2.add(0, change.y(), 0);
                }
            } else if (Math.min(pos1.y(), pos2.y()) == pos1.y()) {
                pos1 = pos1.add(0, change.y(), 0);
            } else {
                pos2 = pos2.add(0, change.y(), 0);
            }
            if (change.z() < 0) {
                if (Math.max(pos1.z(), pos2.z()) == pos1.z()) {
                    pos1 = pos1.add(0, 0, change.z());
                } else {
                    pos2 = pos2.add(0, 0, change.z());
                }
            } else if (Math.min(pos1.z(), pos2.z()) == pos1.z()) {
                pos1 = pos1.add(0, 0, change.z());
            } else {
                pos2 = pos2.add(0, 0, change.z());
            }
        }
        recalculate();
    }

    @Override
    public void shift(BlockVec change) {
        pos1 = pos1.add(change);
        pos2 = pos2.add(change);
        recalculate();
    }

    @Override
    public CuboidRegion copy() {
        return new CuboidRegion(pos1, pos2);
    }

    @Override
    public void forEach(BlockConsumer consumer) {
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    consumer.accept(x, y, z);
                }
            }
        }
    }

    @Override
    public void forEachInChunk(int chunkX, int chunkZ, BlockConsumer consumer) {
        int x0 = Math.max(min.x(), chunkX << 4);
        int x1 = Math.min(max.x(), (chunkX << 4) + 15);
        int z0 = Math.max(min.z(), chunkZ << 4);
        int z1 = Math.min(max.z(), (chunkZ << 4) + 15);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    consumer.accept(x, y, z);
                }
            }
        }
    }

    @Override
    public String toString() {
        return min + " - " + max;
    }
}
