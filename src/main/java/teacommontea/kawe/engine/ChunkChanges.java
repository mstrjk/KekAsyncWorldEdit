package teacommontea.kawe.engine;

import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChunkChanges {

    final int chunkX;
    final int chunkZ;
    private final int minSection;
    private final int[][] sections;
    private volatile Map<Integer, CompoundTag> tiles;
    private volatile Map<Integer, Holder<Biome>> biomes;
    private int count;
    private volatile boolean sealed;

    ChunkChanges(int chunkX, int chunkZ, int minSection, int sectionCount) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSection = minSection;
        this.sections = new int[sectionCount][];
    }

    private void inChunk(int x, int z) {
        if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
            throw new IllegalArgumentException("Position " + x + "," + z + " is outside chunk " + chunkX + "," + chunkZ);
        }
    }

    private int section(int y) {
        int s = (y >> 4) - minSection;
        return s < 0 || s >= sections.length ? -1 : s;
    }

    synchronized void seal() {
        sealed = true;
    }

    private void open() {
        if (sealed) {
            throw new IllegalStateException("Chunk " + chunkX + "," + chunkZ + " was already applied by a streaming edit");
        }
    }

    public synchronized boolean set(int x, int y, int z, BlockState state) {
        open();
        inChunk(x, z);
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sections.length) {
            return false;
        }
        int[] section = sections[s];
        if (section == null) {
            section = new int[4096];
            sections[s] = section;
        }
        int index = ChunkKey.index(x, y, z);
        boolean fresh = section[index] == 0;
        if (fresh) {
            count++;
        }
        section[index] = Block.getId(state) + 1;
        if (tiles != null) {
            tiles.remove((s << 12) | index);
        }
        return fresh;
    }

    public boolean hasTile(int x, int y, int z) {
        int s = section(y);
        return tiles != null && s >= 0 && tiles.containsKey((s << 12) | ChunkKey.index(x, y, z));
    }

    synchronized void tile(int x, int y, int z, CompoundTag tag) {
        open();
        inChunk(x, z);
        int s = section(y);
        if (s < 0 || get(x, y, z) == null) {
            throw new IllegalStateException("Tile data needs a staged block state at " + x + "," + y + "," + z);
        }
        if (tiles == null) {
            tiles = new ConcurrentHashMap<>();
        }
        tiles.put((s << 12) | ChunkKey.index(x, y, z), tag);
    }

    public BlockState get(int x, int y, int z) {
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sections.length) {
            return null;
        }
        int[] section = sections[s];
        if (section == null) {
            return null;
        }
        int id = section[ChunkKey.index(x, y, z)];
        return id == 0 ? null : Block.stateById(id - 1);
    }

    public CompoundTag tileAt(int x, int y, int z) {
        int s = section(y);
        return tiles == null || s < 0 ? null : tiles.get((s << 12) | ChunkKey.index(x, y, z));
    }

    private static int biomeKey(int s, int x, int y, int z) {
        return (s << 6) | (((y & 15) >> 2) << 4) | (((z & 15) >> 2) << 2) | ((x & 15) >> 2);
    }

    public synchronized boolean biome(int x, int y, int z, Holder<Biome> biome) {
        open();
        int s = (y >> 4) - minSection;
        if (s < 0 || s >= sections.length) {
            return false;
        }
        if (biomes == null) {
            biomes = new ConcurrentHashMap<>();
        }
        return biomes.put(biomeKey(s, x, y, z), biome) == null;
    }

    public Holder<Biome> biomeAt(int x, int y, int z) {
        int s = section(y);
        return biomes == null || s < 0 ? null : biomes.get(biomeKey(s, x, y, z));
    }

    synchronized int fill(int index, BlockState state) {
        open();
        int[] section = sections[index];
        int fresh = 4096;
        if (section == null) {
            section = new int[4096];
            sections[index] = section;
        } else {
            fresh = 0;
            for (int id : section) {
                if (id == 0) {
                    fresh++;
                }
            }
        }
        Arrays.fill(section, Block.getId(state) + 1);
        if (tiles != null) {
            tiles.keySet().removeIf(key -> key >> 12 == index);
        }
        count += fresh;
        return fresh;
    }

    int[][] sections() {
        return sections;
    }

    Map<Integer, CompoundTag> tiles() {
        return tiles;
    }

    Map<Integer, Holder<Biome>> biomes() {
        return biomes;
    }

    int minSection() {
        return minSection;
    }

    public synchronized int count() {
        return count;
    }

    public synchronized boolean isEmpty() {
        return count == 0 && (biomes == null || biomes.isEmpty());
    }
}
