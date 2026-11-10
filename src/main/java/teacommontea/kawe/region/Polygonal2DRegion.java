package teacommontea.kawe.region;

import teacommontea.kawe.math.BlockVec2;
import teacommontea.kawe.math.BlockVec;

import java.util.ArrayList;
import java.util.List;

public final class Polygonal2DRegion implements Region {

    private final List<BlockVec2> points = new ArrayList<>();
    private final int worldMinY;
    private final int worldMaxY;
    private BlockVec2 min = new BlockVec2(0, 0);
    private BlockVec2 max = new BlockVec2(0, 0);
    private int minY;
    private int maxY;
    private boolean hasY;
    private long area = -1;

    public Polygonal2DRegion(int worldMinY, int worldMaxY) {
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
    }

    public Polygonal2DRegion(List<BlockVec2> points, int minY, int maxY, int worldMinY, int worldMaxY) {
        this(worldMinY, worldMaxY);
        this.points.addAll(points);
        this.minY = minY;
        this.maxY = maxY;
        this.hasY = true;
        recalculate();
    }

    private void recalculate() {
        area = -1;
        if (points.isEmpty()) {
            min = new BlockVec2(0, 0);
            max = new BlockVec2(0, 0);
            minY = 0;
            maxY = 0;
            return;
        }
        int minX = points.getFirst().x();
        int minZ = points.getFirst().z();
        int maxX = minX;
        int maxZ = minZ;
        for (BlockVec2 v : points) {
            minX = Math.min(minX, v.x());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x());
            maxZ = Math.max(maxZ, v.z());
        }
        int lo = Math.min(minY, maxY);
        int hi = Math.max(minY, maxY);
        minY = Math.clamp(lo, worldMinY, worldMaxY);
        maxY = Math.clamp(hi, worldMinY, worldMaxY);
        min = new BlockVec2(minX, minZ);
        max = new BlockVec2(maxX, maxZ);
    }

    public List<BlockVec2> points() {
        return List.copyOf(points);
    }

    public int size() {
        return points.size();
    }

    public void addPoint(BlockVec position) {
        points.add(new BlockVec2(position.x(), position.z()));
        recalculate();
    }

    public void expandY(int y) {
        if (!hasY) {
            minY = y;
            maxY = y;
            hasY = true;
        } else if (y < minY) {
            minY = y;
        } else if (y > maxY) {
            maxY = y;
        }
        recalculate();
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public void minY(int y) {
        hasY = true;
        minY = y;
        recalculate();
    }

    public void maxY(int y) {
        hasY = true;
        maxY = y;
        recalculate();
    }

    @Override
    public BlockVec min() {
        return min.toBlock(minY);
    }

    @Override
    public BlockVec max() {
        return max.toBlock(maxY);
    }

    @Override
    public long volume() {
        long columns = area;
        if (columns < 0) {
            columns = 0;
            for (int x = min.x(); x <= max.x(); x++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    if (containsFlat(x, z)) {
                        columns++;
                    }
                }
            }
            area = columns;
        }
        return columns * (maxY - minY + 1);
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return y >= minY && y <= maxY && containsFlat(x, z);
    }

    @Override
    public boolean containsColumn(int x, int z) {
        return containsFlat(x, z);
    }

    public boolean containsFlat(int targetX, int targetZ) {
        int count = points.size();
        if (count < 3) {
            return false;
        }
        boolean inside = false;
        int xOld = points.get(count - 1).x();
        int zOld = points.get(count - 1).z();
        for (BlockVec2 point : points) {
            int xNew = point.x();
            int zNew = point.z();
            if (xNew == targetX && zNew == targetZ) {
                return true;
            }
            int x1;
            int x2;
            int z1;
            int z2;
            if (xNew > xOld) {
                x1 = xOld;
                x2 = xNew;
                z1 = zOld;
                z2 = zNew;
            } else {
                x1 = xNew;
                x2 = xOld;
                z1 = zNew;
                z2 = zOld;
            }
            if (x1 <= targetX && targetX <= x2) {
                long cross = ((long) targetZ - z1) * ((long) x2 - x1) - ((long) z2 - z1) * ((long) targetX - x1);
                if (cross == 0) {
                    if ((z1 <= targetZ) == (targetZ <= z2)) {
                        return true;
                    }
                } else if (cross < 0 && x1 != targetX) {
                    inside = !inside;
                }
            }
            xOld = xNew;
            zOld = zNew;
        }
        return inside;
    }

    @Override
    public void expand(BlockVec... changes) throws RegionOperationException {
        for (BlockVec change : changes) {
            if (change.x() != 0 || change.z() != 0) {
                throw new RegionOperationException("kawe.selection.polygon2d.error.expand-only-vertical");
            }
            if (change.y() > 0) {
                maxY += change.y();
            } else {
                minY += change.y();
            }
        }
        recalculate();
    }

    @Override
    public void contract(BlockVec... changes) throws RegionOperationException {
        for (BlockVec change : changes) {
            if (change.x() != 0 || change.z() != 0) {
                throw new RegionOperationException("kawe.selection.polygon2d.error.contract-only-vertical");
            }
            if (change.y() > 0) {
                minY += change.y();
            } else {
                maxY += change.y();
            }
        }
        recalculate();
    }

    @Override
    public void shift(BlockVec change) {
        points.replaceAll(p -> p.add(change.x(), change.z()));
        minY += change.y();
        maxY += change.y();
        recalculate();
    }

    @Override
    public Polygonal2DRegion copy() {
        Polygonal2DRegion copy = new Polygonal2DRegion(points, minY, maxY, worldMinY, worldMaxY);
        copy.hasY = hasY;
        return copy;
    }

    @Override
    public List<BlockVec2> polygonize(int maxPoints) {
        return List.copyOf(points);
    }
}
