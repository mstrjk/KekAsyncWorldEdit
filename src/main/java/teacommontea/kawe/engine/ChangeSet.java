package teacommontea.kawe.engine;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.math.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChangeSet {

    private final KWorld world;
    private final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private final long created;
    private String command = "";
    private volatile UUID diskOwner;
    private volatile int diskIndex = -1;
    private final List<EntityRecord> addedEntities = new ArrayList<>();
    private final List<EntityRecord> removedEntities = new ArrayList<>();

    public ChangeSet(KWorld world) {
        this(world, System.currentTimeMillis());
    }

    public ChangeSet(KWorld world, long created) {
        this.world = world;
        this.created = created;
    }

    public UUID diskOwner() {
        return diskOwner;
    }

    public int diskIndex() {
        return diskIndex;
    }

    public void disk(UUID owner, int index) {
        this.diskOwner = owner;
        this.diskIndex = index;
    }

    public synchronized void dropEntities() {
        addedEntities.clear();
        removedEntities.clear();
    }

    public synchronized boolean hasEntities() {
        return !addedEntities.isEmpty() || !removedEntities.isEmpty();
    }

    public int[] bounds() {
        int[] out = null;
        for (Chunk chunk : chunks.values()) {
            out = chunk.bounds(world, out);
        }
        synchronized (this) {
            for (List<EntityRecord> records : List.of(addedEntities, removedEntities)) {
                for (EntityRecord record : records) {
                    Vec3 at = record.data().position();
                    out = Chunk.grow(out, (int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()));
                }
            }
        }
        return out;
    }

    public List<String> extras() {
        List<String> out = new ArrayList<>();
        boolean biomes = false;
        boolean oldTiles = false;
        boolean newTiles = false;
        for (Chunk chunk : chunks.values()) {
            synchronized (chunk) {
                biomes |= chunk.oldBiomes != null && !chunk.oldBiomes.isEmpty();
                oldTiles |= chunk.oldTiles != null && !chunk.oldTiles.isEmpty();
                newTiles |= chunk.newTiles != null && !chunk.newTiles.isEmpty();
            }
        }
        if (biomes) {
            out.add("biomes");
        }
        synchronized (this) {
            if (!addedEntities.isEmpty()) {
                out.add("+entity");
            }
            if (!removedEntities.isEmpty()) {
                out.add("-entity");
            }
        }
        if (newTiles) {
            out.add("+tile");
        }
        if (oldTiles) {
            out.add("-tile");
        }
        return out;
    }

    public int[] changeAt(int x, int y, int z) {
        Chunk chunk = chunks.get(ChunkKey.of(x >> 4, z >> 4));
        if (chunk == null) {
            return null;
        }
        int section = (y >> 4) - world.minSection();
        if (section < 0) {
            return null;
        }
        return chunk.at((section << 12) | ((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    public Map<BlockState, Long> distribution() {
        Map<Integer, Long> ids = new HashMap<>();
        for (Chunk chunk : chunks.values()) {
            chunk.count(ids);
        }
        Map<BlockState, Long> out = new HashMap<>();
        ids.forEach((id, count) -> out.merge(Block.stateById(id), count, Long::sum));
        return out;
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putLong("created", created);
        root.putString("command", command);
        Palette<BlockState> states = new Palette<>();
        Palette<Holder<Biome>> biomes = new Palette<>();
        ListTag chunkList = new ListTag();
        for (Chunk chunk : chunks.values()) {
            if (!chunk.isEmpty()) {
                chunkList.add(chunk.save(states, biomes));
            }
        }
        root.put("chunks", chunkList);
        ListTag stateList = new ListTag();
        for (BlockState state : states.values) {
            stateList.add(NbtUtils.writeBlockState(state));
        }
        root.put("states", stateList);
        ListTag biomeList = new ListTag();
        for (Holder<Biome> biome : biomes.values) {
            biomeList.add(StringTag.valueOf(biome.unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:plains")));
        }
        root.put("biomes", biomeList);
        synchronized (this) {
            root.put("added", saveEntities(addedEntities));
            root.put("removed", saveEntities(removedEntities));
        }
        return root;
    }

    public static ChangeSet load(KWorld world, CompoundTag root) {
        ChangeSet out = new ChangeSet(world, root.getLongOr("created", 0L));
        out.command = root.getStringOr("command", "");
        ListTag stateList = root.getListOrEmpty("states");
        int[] stateIds = new int[stateList.size()];
        for (int i = 0; i < stateIds.length; i++) {
            stateIds[i] = Block.getId(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, stateList.getCompoundOrEmpty(i)));
        }
        Registry<Biome> registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
        ListTag biomeList = root.getListOrEmpty("biomes");
        List<Holder<Biome>> biomes = new ArrayList<>(biomeList.size());
        for (int i = 0; i < biomeList.size(); i++) {
            Identifier id = Identifier.tryParse(biomeList.getStringOr(i, ""));
            biomes.add(id == null ? null : registry.get(ResourceKey.create(Registries.BIOME, id)).orElse(null));
        }
        for (Tag tag : root.getListOrEmpty("chunks")) {
            if (tag instanceof CompoundTag chunkTag) {
                Chunk chunk = out.chunk(chunkTag.getIntOr("x", 0), chunkTag.getIntOr("z", 0));
                chunk.load(chunkTag, stateIds, biomes);
            }
        }
        out.addedEntities.addAll(loadEntities(root.getListOrEmpty("added")));
        out.removedEntities.addAll(loadEntities(root.getListOrEmpty("removed")));
        return out;
    }

    private static ListTag saveEntities(List<EntityRecord> records) {
        ListTag list = new ListTag();
        for (EntityRecord record : records) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("most", record.id().getMostSignificantBits());
            tag.putLong("least", record.id().getLeastSignificantBits());
            tag.putDouble("x", record.data().position().x());
            tag.putDouble("y", record.data().position().y());
            tag.putDouble("z", record.data().position().z());
            tag.putFloat("yaw", record.data().yaw());
            tag.putFloat("pitch", record.data().pitch());
            tag.put("data", record.data().tag().copy());
            list.add(tag);
        }
        return list;
    }

    private static List<EntityRecord> loadEntities(ListTag list) {
        List<EntityRecord> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompoundOrEmpty(i);
            out.add(new EntityRecord(new UUID(tag.getLongOr("most", 0L), tag.getLongOr("least", 0L)),
                    new EntityData(new Vec3(tag.getDoubleOr("x", 0), tag.getDoubleOr("y", 0), tag.getDoubleOr("z", 0)),
                            tag.getFloatOr("yaw", 0), tag.getFloatOr("pitch", 0), tag.getCompoundOrEmpty("data"))));
        }
        return out;
    }

    private static final class Palette<T> {
        final List<T> values = new ArrayList<>();
        final Map<T, Integer> ids = new HashMap<>();

        int id(T value) {
            return ids.computeIfAbsent(value, v -> {
                values.add(v);
                return values.size() - 1;
            });
        }
    }

    public KWorld world() {
        return world;
    }

    public long created() {
        return created;
    }

    public String command() {
        return command;
    }

    public void command(String value) {
        this.command = value;
    }

    Chunk chunk(int chunkX, int chunkZ) {
        return chunks.computeIfAbsent(ChunkKey.of(chunkX, chunkZ), k -> new Chunk(chunkX, chunkZ));
    }

    public synchronized void entitiesAdded(List<EntityRecord> records) {
        addedEntities.addAll(records);
    }

    public synchronized void entitiesRemoved(List<EntityRecord> records) {
        removedEntities.addAll(records);
    }

    public int replayEntities(boolean undo, boolean events) {
        List<EntityRecord> added;
        List<EntityRecord> removed;
        synchronized (this) {
            added = List.copyOf(addedEntities);
            removed = List.copyOf(removedEntities);
        }
        List<EntityRecord> respawn = undo ? removed : added;
        List<EntityRecord> discard = undo ? added : removed;
        int failed = Entities.removeById(world, discard);
        List<EntityRecord> spawned = Entities.respawn(world, respawn, events);
        failed += respawn.size() - spawned.size();
        synchronized (this) {
            List<EntityRecord> target = undo ? removedEntities : addedEntities;
            target.clear();
            target.addAll(spawned);
        }
        return failed;
    }

    public boolean isEmpty() {
        synchronized (this) {
            if (!addedEntities.isEmpty() || !removedEntities.isEmpty()) {
                return false;
            }
        }
        for (Chunk chunk : chunks.values()) {
            if (!chunk.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public long size() {
        long total = 0;
        for (Chunk chunk : chunks.values()) {
            total += chunk.count();
        }
        return total;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public Map<Long, ChunkChanges> toChanges(boolean undo) {
        Map<Long, ChunkChanges> out = new HashMap<>();
        for (Map.Entry<Long, Chunk> entry : chunks.entrySet()) {
            Chunk chunk = entry.getValue();
            if (chunk.isEmpty()) {
                continue;
            }
            out.put(entry.getKey(), chunk.toChanges(world, undo));
        }
        return out;
    }

    static final class Chunk {
        final int chunkX;
        final int chunkZ;
        int[] index = new int[64];
        int[] from = new int[64];
        int[] to = new int[64];
        int size;
        private IntSlots slots;
        Map<Integer, CompoundTag> oldTiles;
        Map<Integer, CompoundTag> newTiles;
        Map<Integer, Holder<Biome>> oldBiomes;
        Map<Integer, Holder<Biome>> newBiomes;

        Chunk(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        synchronized boolean isEmpty() {
            return size == 0 && (oldBiomes == null || oldBiomes.isEmpty());
        }

        synchronized int[] bounds(KWorld world, int[] out) {
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            for (int i = 0; i < size; i++) {
                int packed = index[i];
                int y = (((packed >>> 12) + world.minSection()) << 4) | ((packed >>> 8) & 15);
                out = grow(out, baseX | (packed & 15), y, baseZ | ((packed >>> 4) & 15));
            }
            if (oldBiomes != null) {
                for (int packed : oldBiomes.keySet()) {
                    int x = baseX | ((packed & 3) << 2);
                    int z = baseZ | (((packed >>> 2) & 3) << 2);
                    int y = (((packed >>> 6) + world.minSection()) << 4) | (((packed >>> 4) & 3) << 2);
                    out = grow(out, x, y, z);
                    out = grow(out, x + 3, y + 3, z + 3);
                }
            }
            return out;
        }

        static int[] grow(int[] out, int x, int y, int z) {
            if (out == null) {
                return new int[]{x, y, z, x, y, z};
            }
            out[0] = Math.min(out[0], x);
            out[1] = Math.min(out[1], y);
            out[2] = Math.min(out[2], z);
            out[3] = Math.max(out[3], x);
            out[4] = Math.max(out[4], y);
            out[5] = Math.max(out[5], z);
            return out;
        }

        private IntSlots slots() {
            IntSlots current = slots;
            if (current == null) {
                current = new IntSlots();
                for (int i = 0; i < size; i++) {
                    current.put(index[i], i);
                }
                slots = current;
            }
            return current;
        }

        synchronized void addAll(int[] packed, int[] fromIds, int[] toIds, int count) {
            if (count == 0) {
                return;
            }
            if (size + count > index.length) {
                int grown = Math.max(index.length << 1, size + count);
                index = Arrays.copyOf(index, grown);
                from = Arrays.copyOf(from, grown);
                to = Arrays.copyOf(to, grown);
            }
            System.arraycopy(packed, 0, index, size, count);
            System.arraycopy(fromIds, 0, from, size, count);
            System.arraycopy(toIds, 0, to, size, count);
            size += count;
            slots = null;
        }

        synchronized int[] at(int packed) {
            int slot = slots().get(packed);
            return slot < 0 ? null : new int[]{from[slot], to[slot]};
        }

        synchronized void count(Map<Integer, Long> ids) {
            for (int i = 0; i < size; i++) {
                ids.merge(to[i], 1L, Long::sum);
            }
        }

        synchronized CompoundTag save(Palette<BlockState> states, Palette<Holder<Biome>> biomes) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", chunkX);
            tag.putInt("z", chunkZ);
            int[] fromIds = new int[size];
            int[] toIds = new int[size];
            for (int i = 0; i < size; i++) {
                fromIds[i] = states.id(Block.stateById(from[i]));
                toIds[i] = states.id(Block.stateById(to[i]));
            }
            tag.putIntArray("index", Arrays.copyOf(index, size));
            tag.putIntArray("from", fromIds);
            tag.putIntArray("to", toIds);
            tag.put("oldTiles", saveTiles(oldTiles));
            tag.put("newTiles", saveTiles(newTiles));
            if (oldBiomes != null && !oldBiomes.isEmpty()) {
                int[] biomeData = new int[oldBiomes.size() * 3];
                int i = 0;
                for (Map.Entry<Integer, Holder<Biome>> entry : oldBiomes.entrySet()) {
                    biomeData[i++] = entry.getKey();
                    biomeData[i++] = biomes.id(entry.getValue());
                    biomeData[i++] = biomes.id(newBiomes.get(entry.getKey()));
                }
                tag.putIntArray("biomes", biomeData);
            }
            return tag;
        }

        synchronized void load(CompoundTag tag, int[] stateIds, List<Holder<Biome>> biomes) {
            int[] indexes = tag.getIntArray("index").orElse(new int[0]);
            int[] fromIds = tag.getIntArray("from").orElse(new int[0]);
            int[] toIds = tag.getIntArray("to").orElse(new int[0]);
            if (fromIds.length != indexes.length || toIds.length != indexes.length) {
                throw new IllegalStateException("Corrupt history chunk " + chunkX + "," + chunkZ);
            }
            for (int i = 0; i < indexes.length; i++) {
                add(indexes[i], stateIds[fromIds[i]], stateIds[toIds[i]]);
            }
            oldTiles = loadTiles(tag.getListOrEmpty("oldTiles"));
            newTiles = loadTiles(tag.getListOrEmpty("newTiles"));
            int[] biomeData = tag.getIntArray("biomes").orElse(new int[0]);
            for (int i = 0; i + 2 < biomeData.length; i += 3) {
                Holder<Biome> before = biomes.get(biomeData[i + 1]);
                Holder<Biome> after = biomes.get(biomeData[i + 2]);
                if (before != null && after != null) {
                    biome(biomeData[i], before, after);
                }
            }
        }

        private static ListTag saveTiles(Map<Integer, CompoundTag> tiles) {
            ListTag list = new ListTag();
            if (tiles != null) {
                for (Map.Entry<Integer, CompoundTag> entry : tiles.entrySet()) {
                    if (entry.getValue() != null) {
                        CompoundTag tag = new CompoundTag();
                        tag.putInt("pos", entry.getKey());
                        tag.put("data", entry.getValue().copy());
                        list.add(tag);
                    }
                }
            }
            return list;
        }

        private static Map<Integer, CompoundTag> loadTiles(ListTag list) {
            if (list.isEmpty()) {
                return null;
            }
            Map<Integer, CompoundTag> out = new HashMap<>();
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                out.put(tag.getIntOr("pos", 0), tag.getCompoundOrEmpty("data"));
            }
            return out;
        }

        synchronized int count() {
            return size;
        }

        synchronized ChunkChanges toChanges(KWorld world, boolean undo) {
            ChunkChanges changes = new ChunkChanges(chunkX, chunkZ, world.minSection(), world.sections());
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            Map<Integer, CompoundTag> tiles = undo ? oldTiles : newTiles;
            for (int i = 0; i < size; i++) {
                int packed = index[i];
                int section = packed >>> 12;
                int local = packed & 4095;
                int x = baseX | (local & 15);
                int y = ((section + world.minSection()) << 4) | (local >>> 8);
                int z = baseZ | ((local >>> 4) & 15);
                changes.set(x, y, z, Block.stateById(undo ? from[i] : to[i]));
                CompoundTag tag = tiles == null ? null : tiles.get(packed);
                if (tag != null) {
                    changes.tile(x, y, z, tag.copy());
                }
            }
            Map<Integer, Holder<Biome>> biomes = undo ? oldBiomes : newBiomes;
            if (biomes != null) {
                for (Map.Entry<Integer, Holder<Biome>> biome : biomes.entrySet()) {
                    int packed = biome.getKey();
                    int section = packed >>> 6;
                    int x = baseX | ((packed & 3) << 2);
                    int z = baseZ | (((packed >>> 2) & 3) << 2);
                    int y = ((section + world.minSection()) << 4) | (((packed >>> 4) & 3) << 2);
                    changes.biome(x, y, z, biome.getValue());
                }
            }
            return changes;
        }

        synchronized void add(int packed, int fromId, int toId) {
            IntSlots lookup = slots();
            int existing = lookup.get(packed);
            if (existing >= 0) {
                to[existing] = toId;
                return;
            }
            if (size == index.length) {
                int grown = size << 1;
                index = Arrays.copyOf(index, grown);
                from = Arrays.copyOf(from, grown);
                to = Arrays.copyOf(to, grown);
            }
            index[size] = packed;
            from[size] = fromId;
            to[size] = toId;
            lookup.put(packed, size);
            size++;
        }

        synchronized void oldTile(int packed, CompoundTag tag) {
            if (oldTiles == null) {
                oldTiles = new HashMap<>();
            }
            oldTiles.putIfAbsent(packed, tag);
        }

        synchronized void newTile(int packed, CompoundTag tag) {
            if (newTiles == null) {
                newTiles = new HashMap<>();
            }
            if (tag == null) {
                newTiles.remove(packed);
            } else {
                newTiles.put(packed, tag);
            }
        }

        synchronized void biome(int packed, Holder<Biome> from, Holder<Biome> to) {
            if (oldBiomes == null) {
                oldBiomes = new HashMap<>();
                newBiomes = new HashMap<>();
            }
            oldBiomes.putIfAbsent(packed, from);
            newBiomes.put(packed, to);
        }
    }
}
