package teacommontea.kawe.region.selector;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.FuzzyRegion;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FuzzySelector implements Selector {

    private static final int DEPTH = 256;
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final List<BlockVec> positions = new ArrayList<>();
    private final List<BlockVec> pending = new ArrayList<>();
    private final LongOpenHashSet blocks = new LongOpenHashSet();

    @Override
    public String typeName() {
        return "fuzzy";
    }

    @Override
    public boolean needsWorld() {
        return true;
    }

    @Override
    public synchronized void resolve(Edit reader) {
        for (BlockVec start : pending) {
            if (start.y() < reader.minY() || start.y() > reader.maxY()) {
                continue;
            }
            Block type = reader.get(start.x(), start.y(), start.z()).getBlock();
            LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
            LongOpenHashSet seen = new LongOpenHashSet();
            long origin = BlockPos.asLong(start.x(), start.y(), start.z());
            queue.enqueue(origin);
            seen.add(origin);
            int depth = 0;
            while (!queue.isEmpty() && depth <= DEPTH) {
                int layer = queue.size();
                for (int i = 0; i < layer; i++) {
                    long at = queue.dequeueLong();
                    int x = BlockPos.getX(at);
                    int y = BlockPos.getY(at);
                    int z = BlockPos.getZ(at);
                    if (reader.get(x, y, z).getBlock() != type) {
                        continue;
                    }
                    blocks.add(at);
                    for (int[] face : FACES) {
                        int ny = y + face[1];
                        long next = BlockPos.asLong(x + face[0], ny, z + face[2]);
                        if (ny >= reader.minY() && ny <= reader.maxY() && seen.add(next)) {
                            queue.enqueue(next);
                        }
                    }
                }
                depth++;
            }
        }
        pending.clear();
    }

    @Override
    public synchronized boolean selectPrimary(BlockVec pos) {
        positions.clear();
        pending.clear();
        blocks.clear();
        positions.add(pos);
        pending.add(pos);
        return true;
    }

    @Override
    public synchronized boolean selectSecondary(BlockVec pos) {
        positions.add(pos);
        pending.add(pos);
        return true;
    }

    @Override
    public synchronized void explainPrimary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selector.selector.fuzzy.pos1", pos.toString(), "(" + blocks.size() + ")");
    }

    @Override
    public synchronized void explainSecondary(Audience to, BlockVec pos) {
        Msg.print(to, "kawe.selector.selector.fuzzy.pos2", pos.toString(), "(" + blocks.size() + ")");
    }

    @Override
    public synchronized boolean isDefined() {
        return !blocks.isEmpty();
    }

    @Override
    public synchronized Region region() throws IncompleteRegionException {
        if (blocks.isEmpty()) {
            throw new IncompleteRegionException();
        }
        return new FuzzyRegion(blocks);
    }

    @Override
    public synchronized Optional<Region> partialRegion() {
        return blocks.isEmpty() ? Optional.empty() : Optional.of(new FuzzyRegion(blocks));
    }

    @Override
    public synchronized BlockVec primary() throws IncompleteRegionException {
        if (positions.isEmpty()) {
            throw new IncompleteRegionException();
        }
        return positions.getFirst();
    }

    @Override
    public synchronized void learn(Region changed) {
        blocks.clear();
        changed.forEach((x, y, z) -> blocks.add(BlockPos.asLong(x, y, z)));
    }

    @Override
    public synchronized void clear() {
        positions.clear();
        pending.clear();
        blocks.clear();
    }

    @Override
    public synchronized List<Component> info() {
        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            lines.add(Component.text("Position " + i + ": " + positions.get(i)));
        }
        return lines;
    }

}
