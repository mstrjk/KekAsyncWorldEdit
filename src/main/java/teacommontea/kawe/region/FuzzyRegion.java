package teacommontea.kawe.region;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import teacommontea.kawe.math.BlockVec;

public final class FuzzyRegion implements Region {

    private final LongOpenHashSet blocks;
    private final BlockVec min;
    private final BlockVec max;

    public FuzzyRegion(LongOpenHashSet blocks) {
        this.blocks = new LongOpenHashSet(blocks);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : this.blocks) {
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        this.min = this.blocks.isEmpty() ? BlockVec.ZERO : new BlockVec(minX, minY, minZ);
        this.max = this.blocks.isEmpty() ? BlockVec.ZERO : new BlockVec(maxX, maxY, maxZ);
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
        return blocks.size();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return blocks.contains(BlockPos.asLong(x, y, z));
    }

    @Override
    public void expand(BlockVec... changes) throws RegionOperationException {
        throw new RegionOperationException("kawe.error.selection-expand");
    }

    @Override
    public void contract(BlockVec... changes) throws RegionOperationException {
        throw new RegionOperationException("kawe.error.selection-contract");
    }

    @Override
    public void shift(BlockVec change) throws RegionOperationException {
        throw new RegionOperationException("kawe.error.selection-shift");
    }

    @Override
    public Region copy() {
        return this;
    }
}
