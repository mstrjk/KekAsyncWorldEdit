package teacommontea.kawe.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import teacommontea.kawe.Sched;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public final class ChunkSnap {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private record Section(PalettedContainer<BlockState> blocks, PalettedContainerRO<Holder<Biome>> biomes) {
    }

    private final int minY;
    private final int maxY;
    private final int minSection;
    private final int lowSection;
    private final int highSection;
    private final Section[] sections;
    private final Map<Integer, CompoundTag> tiles;
    private final int[] surface;

    private ChunkSnap(KWorld world, int lowSection, int highSection, Section[] sections, Map<Integer, CompoundTag> tiles) {
        this.minY = world.minY();
        this.maxY = world.maxY();
        this.minSection = world.minSection();
        this.lowSection = lowSection;
        this.highSection = highSection;
        this.sections = sections;
        this.tiles = tiles;
        this.surface = new int[256];
        Arrays.fill(surface, Integer.MIN_VALUE);
    }

    static ChunkSnap capture(LevelChunk chunk, KWorld world, boolean withTiles, int lowSection, int highSection) {
        LevelChunkSection[] live = chunk.getSections();
        Section[] sections = new Section[live.length];
        int low = Math.max(lowSection, world.minSection());
        int high = Math.min(highSection, world.minSection() + live.length - 1);
        for (int i = low - world.minSection(); i <= high - world.minSection(); i++) {
            LevelChunkSection section = live[i];
            if (section != null) {
                sections[i] = new Section(section.hasOnlyAir() ? null : section.getStates().copy(), section.getBiomes().copy());
            }
        }
        Map<Integer, CompoundTag> tiles = null;
        if (withTiles) {
            Map<Integer, CompoundTag> captured = new HashMap<>();
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey();
                BlockEntity blockEntity = entry.getValue();
                int key = packTile(pos.getX(), pos.getY(), pos.getZ(), world.minY(), world.maxY());
                int sectionY = pos.getY() >> 4;
                if (key < 0 || sectionY < low || sectionY > high || blockEntity.isRemoved() || !blockEntity.getType().isValid(chunk.getBlockState(pos))) {
                    continue;
                }
                try {
                    captured.put(key, blockEntity.saveWithoutMetadata(world.registries()));
                } catch (RuntimeException error) {
                    Sched.plugin().getLogger().log(Level.WARNING, "Skipping unreadable block entity at " + pos.toShortString(), error);
                }
            }
            tiles = Map.copyOf(captured);
        }
        return new ChunkSnap(world, low, high, sections, tiles);
    }

    static int packTile(int x, int y, int z, int minY, int maxY) {
        if (y < minY || y > maxY) {
            return -1;
        }
        return ((y - minY) << 8) | ((z & 15) << 4) | (x & 15);
    }

    boolean captured(int index) {
        int sectionY = index + minSection;
        return sectionY >= lowSection && sectionY <= highSection && index < sections.length;
    }

    PalettedContainer<BlockState> states(int index) {
        Section section = sections[index];
        return section == null ? null : section.blocks;
    }

    public boolean hasTiles() {
        return tiles != null;
    }

    public boolean covers(int fromSection, int toSection) {
        return fromSection >= lowSection && toSection <= highSection;
    }

    private void require(int sectionY) {
        if (sectionY < lowSection || sectionY > highSection) {
            throw new IllegalStateException("Section " + sectionY + " is outside the captured range " + lowSection + " to " + highSection);
        }
    }

    public BlockState get(int x, int y, int z) {
        require(y >> 4);
        int index = (y >> 4) - minSection;
        if (index < 0 || index >= sections.length || sections[index] == null || sections[index].blocks == null) {
            return AIR;
        }
        return sections[index].blocks.get(x & 15, y & 15, z & 15);
    }

    public Holder<Biome> biome(int x, int y, int z) {
        int clamped = Math.clamp(y, minY, maxY);
        require(clamped >> 4);
        int index = (clamped >> 4) - minSection;
        Section section = index < 0 || index >= sections.length ? null : sections[index];
        return section == null ? null : section.biomes.get((x & 15) >> 2, (clamped & 15) >> 2, (z & 15) >> 2);
    }

    public CompoundTag tile(int x, int y, int z) {
        if (tiles == null) {
            throw new IllegalStateException("Block entities were not captured for this edit");
        }
        require(y >> 4);
        int key = packTile(x, y, z, minY, maxY);
        CompoundTag tag = key < 0 ? null : tiles.get(key);
        return tag == null ? null : tag.copy();
    }

    public int surface(int x, int z) {
        require(minY >> 4);
        require(maxY >> 4);
        int column = ((z & 15) << 4) | (x & 15);
        int cached = surface[column];
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        int found = minY - 1;
        for (int y = maxY; y >= minY; y--) {
            if (sectionEmpty(y >> 4)) {
                y = ((y >> 4) << 4);
                continue;
            }
            if (!get(x, y, z).isAir()) {
                found = y;
                break;
            }
        }
        surface[column] = found;
        return found;
    }

    public boolean sectionEmpty(int sectionY) {
        int index = sectionY - minSection;
        return index < 0 || index >= sections.length || sections[index] == null || sections[index].blocks == null;
    }
}
