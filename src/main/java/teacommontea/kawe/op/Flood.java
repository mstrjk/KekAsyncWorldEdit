package teacommontea.kawe.op;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class Flood {

    public static final BlockVec[] AXES = {BlockVec.UNIT_MINUS_Y, BlockVec.UNIT_Y, BlockVec.UNIT_MINUS_X, BlockVec.UNIT_X,
            BlockVec.UNIT_MINUS_Z, BlockVec.UNIT_Z};
    public static final BlockVec[] NON_RISING = {BlockVec.UNIT_X, BlockVec.UNIT_MINUS_X, BlockVec.UNIT_Z, BlockVec.UNIT_MINUS_Z,
            BlockVec.UNIT_MINUS_Y};

    @FunctionalInterface
    public interface Step {
        boolean test(int fromX, int fromY, int fromZ, int x, int y, int z);
    }

    @FunctionalInterface
    public interface Visit {
        boolean apply(int x, int y, int z);
    }

    private Flood() {
    }

    public static void prefetch(Edit edit, BlockVec center, int reach, int minY, int maxY) {
        List<Long> keys = new ArrayList<>();
        for (int cx = (center.x() - reach) >> 4; cx <= (center.x() + reach) >> 4; cx++) {
            for (int cz = (center.z() - reach) >> 4; cz <= (center.z() + reach) >> 4; cz++) {
                keys.add(ChunkKey.of(cx, cz));
            }
        }
        edit.prefetch(keys, Math.max(minY, edit.minY()), Math.min(maxY, edit.maxY()));
    }

    public static long run(Edit edit, Collection<BlockVec> seeds, BlockVec[] directions, int maxDepth, Step step, Visit visit) {
        int minY = edit.minY();
        int maxY = edit.maxY();
        LongOpenHashSet visited = new LongOpenHashSet();
        LongArrayList queue = new LongArrayList();
        for (BlockVec seed : seeds) {
            long key = BlockPos.asLong(seed.x(), seed.y(), seed.z());
            if (seed.y() >= minY && seed.y() <= maxY && !visited.contains(key) && step.test(seed.x(), seed.y(), seed.z(), seed.x(), seed.y(), seed.z())) {
                visited.add(key);
                queue.add(key);
            }
        }
        long affected = 0;
        LongArrayList next = new LongArrayList();
        for (int depth = 0; !queue.isEmpty() && depth <= maxDepth; depth++) {
            for (int i = 0; i < queue.size(); i++) {
                long from = queue.getLong(i);
                int fx = BlockPos.getX(from);
                int fy = BlockPos.getY(from);
                int fz = BlockPos.getZ(from);
                if (visit.apply(fx, fy, fz)) {
                    affected++;
                }
                if (depth == maxDepth) {
                    continue;
                }
                for (BlockVec direction : directions) {
                    int y = fy + direction.y();
                    if (y < minY || y > maxY) {
                        continue;
                    }
                    int x = fx + direction.x();
                    int z = fz + direction.z();
                    long key = BlockPos.asLong(x, y, z);
                    if (!visited.contains(key) && step.test(fx, fy, fz, x, y, z)) {
                        visited.add(key);
                        next.add(key);
                    }
                }
            }
            LongArrayList swap = queue;
            queue = next;
            next = swap;
            next.clear();
        }
        return affected;
    }
}
