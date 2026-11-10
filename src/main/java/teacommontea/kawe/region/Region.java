package teacommontea.kawe.region;

import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.math.BlockVec2;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public interface Region {

    BlockVec min();

    BlockVec max();

    long volume();

    boolean contains(int x, int y, int z);

    default boolean contains(BlockVec v) {
        return contains(v.x(), v.y(), v.z());
    }

    default boolean containsColumn(int x, int z) {
        BlockVec min = min();
        BlockVec max = max();
        if (x < min.x() || x > max.x() || z < min.z() || z > max.z()) {
            return false;
        }
        for (int y = min.y(); y <= max.y(); y++) {
            if (contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    void expand(BlockVec... changes) throws RegionOperationException;

    void contract(BlockVec... changes) throws RegionOperationException;

    void shift(BlockVec change) throws RegionOperationException;

    default Vec3 center() {
        return min().toVec3().add(max().toVec3()).divide(2);
    }

    default int width() {
        return max().x() - min().x() + 1;
    }

    default int height() {
        return max().y() - min().y() + 1;
    }

    default int length() {
        return max().z() - min().z() + 1;
    }

    Region copy();

    default Set<Long> chunks() {
        BlockVec min = min();
        BlockVec max = max();
        Set<Long> out = new LinkedHashSet<>();
        for (int cx = min.x() >> 4; cx <= max.x() >> 4; cx++) {
            for (int cz = min.z() >> 4; cz <= max.z() >> 4; cz++) {
                out.add(ChunkKey.of(cx, cz));
            }
        }
        return out;
    }

    default void forEach(BlockConsumer consumer) {
        BlockVec min = min();
        BlockVec max = max();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    if (contains(x, y, z)) {
                        consumer.accept(x, y, z);
                    }
                }
            }
        }
    }

    default void forEachInChunk(int chunkX, int chunkZ, BlockConsumer consumer) {
        BlockVec min = min();
        BlockVec max = max();
        int x0 = Math.max(min.x(), chunkX << 4);
        int x1 = Math.min(max.x(), (chunkX << 4) + 15);
        int z0 = Math.max(min.z(), chunkZ << 4);
        int z1 = Math.min(max.z(), (chunkZ << 4) + 15);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    if (contains(x, y, z)) {
                        consumer.accept(x, y, z);
                    }
                }
            }
        }
    }

    default List<BlockVec2> polygonize(int maxPoints) {
        BlockVec min = min();
        BlockVec max = max();
        List<BlockVec2> points = new ArrayList<>(4);
        points.add(new BlockVec2(min.x(), min.z()));
        points.add(new BlockVec2(min.x(), max.z()));
        points.add(new BlockVec2(max.x(), max.z()));
        points.add(new BlockVec2(max.x(), min.z()));
        return points;
    }

    @FunctionalInterface
    interface BlockConsumer {
        void accept(int x, int y, int z);
    }
}
