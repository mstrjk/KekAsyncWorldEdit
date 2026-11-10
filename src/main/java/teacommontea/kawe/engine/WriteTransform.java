package teacommontea.kawe.engine;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

public interface WriteTransform {

    interface Writer {
        boolean set(int x, int y, int z, BlockState state, CompoundTag tile);

        boolean setBiome(int x, int y, int z, Holder<Biome> biome);
    }

    boolean write(Writer out, int x, int y, int z, BlockState state, CompoundTag tile);

    boolean writeBiome(Writer out, int x, int y, int z, Holder<Biome> biome);
}
