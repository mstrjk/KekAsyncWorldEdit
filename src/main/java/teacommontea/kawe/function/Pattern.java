package teacommontea.kawe.function;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.engine.Extent;

@FunctionalInterface
public interface Pattern {

    BlockState state(int x, int y, int z);

    default CompoundTag tile(int x, int y, int z) {
        return null;
    }

    default Pattern pick(int x, int y, int z) {
        return this;
    }

    default boolean apply(Extent dest, int x, int y, int z) {
        BlockState state = state(x, y, z);
        return state != null && dest.set(x, y, z, state);
    }

    static Pattern of(BlockState state) {
        return new Single(state);
    }

    record Single(BlockState block) implements Pattern {
        @Override
        public BlockState state(int x, int y, int z) {
            return block;
        }
    }
}
