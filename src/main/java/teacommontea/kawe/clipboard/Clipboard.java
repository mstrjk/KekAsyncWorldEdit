package teacommontea.kawe.clipboard;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.engine.EntityData;
import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.schematic.ImportReport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Clipboard implements Extent {

    private static final int AIR = Block.getId(Blocks.AIR.defaultBlockState());
    private static final long MAX_VOLUME = Integer.MAX_VALUE - 8;

    private final Region shape;
    private final BlockVec min;
    private final BlockVec max;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] states;
    private final Map<Integer, CompoundTag> tiles = new ConcurrentHashMap<>();
    private final List<EntityData> entities = new ArrayList<>();
    private int cellMinX;
    private int cellMinY;
    private int cellMinZ;
    private int cellsX;
    private int cellsZ;
    private int[] biomes;
    private Registry<Biome> biomeRegistry;
    private volatile BlockVec origin;
    private volatile ImportReport report = ImportReport.CLEAN;

    public Clipboard(Region region, BlockVec origin) {
        this.shape = region.copy();
        this.min = shape.min();
        this.max = shape.max();
        long lx = (long) max.x() - min.x() + 1;
        long ly = (long) max.y() - min.y() + 1;
        long lz = (long) max.z() - min.z() + 1;
        long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact(lx, ly), lz);
        } catch (ArithmeticException e) {
            volume = Long.MAX_VALUE;
        }
        if (lx <= 0 || ly <= 0 || lz <= 0 || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Clipboard too large or empty: " + lx + "x" + ly + "x" + lz);
        }
        this.sizeX = (int) lx;
        this.sizeY = (int) ly;
        this.sizeZ = (int) lz;
        this.states = new int[(int) volume];
        if (AIR != 0) {
            Arrays.fill(states, AIR);
        }
        this.origin = origin;
    }

    public Region region() {
        return shape.copy();
    }

    public BlockVec min() {
        return min;
    }

    public BlockVec max() {
        return max;
    }

    public BlockVec dimensions() {
        return new BlockVec(sizeX, sizeY, sizeZ);
    }

    public BlockVec origin() {
        return origin;
    }

    public void origin(BlockVec value) {
        this.origin = value;
    }

    public long volume() {
        return states.length;
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= min.x() && x <= max.x() && y >= min.y() && y <= max.y() && z >= min.z() && z <= max.z();
    }

    public boolean contains(int x, int y, int z) {
        return inBounds(x, y, z) && shape.contains(x, y, z);
    }

    private int index(int x, int y, int z) {
        return ((y - min.y()) * sizeZ + (z - min.z())) * sizeX + (x - min.x());
    }

    @Override
    public BlockState get(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        return Block.stateById(states[index(x, y, z)]);
    }

    @Override
    public boolean set(int x, int y, int z, BlockState state) {
        return set(x, y, z, state, null);
    }

    @Override
    public boolean set(int x, int y, int z, BlockState state, CompoundTag tile) {
        if (!inBounds(x, y, z)) {
            return false;
        }
        int index = index(x, y, z);
        states[index] = Block.getId(state);
        if (tile != null && state.hasBlockEntity()) {
            tiles.put(index, tile.copy());
        } else {
            tiles.remove(index);
        }
        return true;
    }

    @Override
    public CompoundTag tile(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            return null;
        }
        CompoundTag tag = tiles.get(index(x, y, z));
        return tag == null ? null : tag.copy();
    }

    public int tileCount() {
        return tiles.size();
    }

    public void enableBiomes(Registry<Biome> registry) {
        this.biomeRegistry = registry;
        this.cellMinX = Math.floorDiv(min.x(), 4);
        this.cellMinY = Math.floorDiv(min.y(), 4);
        this.cellMinZ = Math.floorDiv(min.z(), 4);
        this.cellsX = Math.floorDiv(max.x(), 4) - cellMinX + 1;
        int cellsY = Math.floorDiv(max.y(), 4) - cellMinY + 1;
        this.cellsZ = Math.floorDiv(max.z(), 4) - cellMinZ + 1;
        this.biomes = new int[cellsX * cellsY * cellsZ];
        Arrays.fill(biomes, -1);
    }

    public boolean hasBiomes() {
        return biomes != null;
    }

    private int cell(int x, int y, int z) {
        int cx = Math.floorDiv(x, 4) - cellMinX;
        int cy = Math.floorDiv(y, 4) - cellMinY;
        int cz = Math.floorDiv(z, 4) - cellMinZ;
        return (cy * cellsZ + cz) * cellsX + cx;
    }

    @Override
    public Holder<Biome> biome(int x, int y, int z) {
        if (biomes == null || !inBounds(x, y, z)) {
            return null;
        }
        int id = biomes[cell(x, y, z)];
        if (id < 0) {
            return null;
        }
        Biome biome = biomeRegistry.byId(id);
        return biome == null ? null : biomeRegistry.wrapAsHolder(biome);
    }

    @Override
    public boolean setBiome(int x, int y, int z, Holder<Biome> biome) {
        if (biomes == null) {
            throw new IllegalStateException("Biomes are not enabled for this clipboard");
        }
        if (!inBounds(x, y, z) || biome == null) {
            return false;
        }
        biomes[cell(x, y, z)] = biomeRegistry.getId(biome.value());
        return true;
    }

    public void addEntity(EntityData entity) {
        synchronized (entities) {
            entities.add(entity);
        }
    }

    public void addEntities(Collection<EntityData> values) {
        synchronized (entities) {
            entities.addAll(values);
        }
    }

    public List<EntityData> entities() {
        synchronized (entities) {
            return List.copyOf(entities);
        }
    }

    public ImportReport report() {
        return report;
    }

    public void report(ImportReport value) {
        this.report = value;
    }

    @Override
    public int minY() {
        return min.y();
    }

    @Override
    public int maxY() {
        return max.y();
    }
}
