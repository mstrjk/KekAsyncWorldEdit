package teacommontea.kawe.engine;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

public interface Extent {

    BlockState get(int x, int y, int z);

    boolean set(int x, int y, int z, BlockState state);

    default boolean set(int x, int y, int z, BlockState state, CompoundTag tile) {
        return set(x, y, z, state);
    }

    default CompoundTag tile(int x, int y, int z) {
        return null;
    }

    default Holder<Biome> biome(int x, int y, int z) {
        return null;
    }

    default boolean setBiome(int x, int y, int z, Holder<Biome> biome) {
        return false;
    }

    int minY();

    int maxY();
}
