package teacommontea.kawe.schematic;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.Sched;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.engine.EntityData;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.CuboidRegion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class Sponge {

    private Sponge() {
    }

    public static boolean isSponge(CompoundTag root) {
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        return schematic.contains("Version") && (schematic.contains("Blocks") || schematic.contains("BlockData") || schematic.contains("Palette"));
    }

    public static int version(CompoundTag root) {
        return root.getCompound("Schematic").orElse(root).getIntOr("Version", 1);
    }

    public static Clipboard read(CompoundTag root, Registry<Biome> biomes, boolean brokenEntities) throws IOException {
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        int version = schematic.getIntOr("Version", 1);
        int dataVersion = schematic.getIntOr("DataVersion", 1631);
        int width = schematic.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schematic.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schematic.getShortOr("Length", (short) 0) & 0xFFFF;
        int[] offsetParts = schematic.getIntArray("Offset").orElse(new int[3]);
        BlockVec offset = offsetParts.length == 3 ? new BlockVec(offsetParts[0], offsetParts[1], offsetParts[2]) : BlockVec.ZERO;
        BlockVec min;
        BlockVec origin;
        CompoundTag palette;
        byte[] data;
        ListTag tiles;
        CompoundTag metadata = schematic.getCompoundOrEmpty("Metadata");
        if (version >= 3) {
            int[] stored = metadata.getCompoundOrEmpty("WorldEdit").getIntArray("Origin").orElse(null);
            origin = stored != null && stored.length == 3 ? new BlockVec(stored[0], stored[1], stored[2]) : BlockVec.ZERO;
            min = origin.add(offset);
            CompoundTag blocks = schematic.getCompoundOrEmpty("Blocks");
            palette = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
            tiles = blocks.getListOrEmpty("BlockEntities");
        } else {
            min = offset;
            if (metadata.contains("WEOffsetX")) {
                origin = min.subtract(metadata.getIntOr("WEOffsetX", 0), metadata.getIntOr("WEOffsetY", 0), metadata.getIntOr("WEOffsetZ", 0));
            } else {
                origin = min;
            }
            palette = schematic.getCompoundOrEmpty("Palette");
            data = schematic.getByteArray("BlockData").orElse(new byte[0]);
            tiles = schematic.contains("BlockEntities") ? schematic.getListOrEmpty("BlockEntities") : schematic.getListOrEmpty("TileEntities");
        }
        if (width == 0 || height == 0 || length == 0) {
            throw new IOException("Empty schematic");
        }
        Clipboard clipboard = new Clipboard(new CuboidRegion(min, min.add(width - 1, height - 1, length - 1)), origin);
        ImportReport.Builder report = new ImportReport.Builder();
        report.dataVersion(dataVersion, Fixer.current());
        Map<Integer, BlockState> states = new HashMap<>();
        Map<Integer, String> unknown = new HashMap<>();
        Map<Integer, String> approximated = new HashMap<>();
        List<String> keys = new ArrayList<>(palette.keySet());
        List<Fixer.Resolved> fixed = Fixer.states(keys, dataVersion);
        for (int k = 0; k < keys.size(); k++) {
            String key = keys.get(k);
            Fixer.Resolved resolved = fixed.get(k);
            int id = palette.getIntOr(key, 0);
            if (resolved.state() == null) {
                unknown.put(id, key);
                states.put(id, Blocks.AIR.defaultBlockState());
            } else {
                if (resolved.approximated()) {
                    approximated.put(id, key);
                }
                states.put(id, resolved.state());
            }
        }
        long total = (long) width * (long) height * (long) length;
        VarIntReader reader = new VarIntReader(data);
        long layer = (long) width * (long) length;
        for (long index = 0; index < total; index++) {
            int value = reader.next();
            BlockState state = states.get(value);
            if (state == null) {
                throw new IOException("Block palette has no entry for index " + value);
            }
            String missing = unknown.get(value);
            if (missing != null) {
                report.unknownBlock(missing);
            } else {
                String loose = approximated.get(value);
                if (loose != null) {
                    report.fallbackMapping(loose);
                }
            }
            int y = (int) (index / layer);
            long rest = index % layer;
            clipboard.set(min.x() + (int) (rest % width), min.y() + y, min.z() + (int) (rest / width), state);
        }
        List<Fixer.Tile> pendingTiles = new ArrayList<>();
        List<BlockVec> pendingPositions = new ArrayList<>();
        for (Tag tag : tiles) {
            if (!(tag instanceof CompoundTag tile)) {
                report.invalidBlockEntity("entry is not a compound");
                continue;
            }
            int[] pos = tile.getIntArray("Pos").orElse(null);
            String id = tile.getStringOr("Id", "");
            if (pos == null || pos.length != 3) {
                report.invalidBlockEntity(id + " has no position");
                continue;
            }
            int x = min.x() + pos[0];
            int y = min.y() + pos[1];
            int z = min.z() + pos[2];
            if (!clipboard.inBounds(x, y, z)) {
                report.invalidBlockEntity(id + " outside bounds at " + pos[0] + "," + pos[1] + "," + pos[2]);
                continue;
            }
            BlockState at = clipboard.get(x, y, z);
            if (!at.hasBlockEntity()) {
                if (LegacyCompat.obsolete(id)) {
                    continue;
                }
                report.invalidBlockEntity(id + " at " + pos[0] + "," + pos[1] + "," + pos[2] + " has no matching block");
                continue;
            }
            CompoundTag body;
            if (version >= 3) {
                body = tile.getCompoundOrEmpty("Data").copy();
            } else {
                body = tile.copy();
                body.remove("Pos");
            }
            body.remove("Id");
            body.putString("id", id);
            pendingTiles.add(new Fixer.Tile(body, BlockStateParser.serialize(at)));
            pendingPositions.add(new BlockVec(x, y, z));
        }
        List<CompoundTag> fixedTiles = Fixer.blockEntities(pendingTiles, dataVersion);
        for (int i = 0; i < fixedTiles.size(); i++) {
            CompoundTag body = fixedTiles.get(i);
            body.remove("x");
            body.remove("y");
            body.remove("z");
            BlockVec at = pendingPositions.get(i);
            clipboard.set(at.x(), at.y(), at.z(), clipboard.get(at.x(), at.y(), at.z()), body);
        }
        readBiomes(schematic, version, clipboard, min, width, height, length, biomes);
        readEntities(schematic, version, dataVersion, clipboard, version >= 3 || brokenEntities ? min : BlockVec.ZERO, report);
        clipboard.report(report.build());
        return clipboard;
    }

    private static void readBiomes(CompoundTag schematic, int version, Clipboard clipboard, BlockVec min, int width, int height, int length, Registry<Biome> registry) throws IOException {
        CompoundTag palette;
        byte[] data;
        boolean flat;
        if (version >= 3) {
            CompoundTag biomes = schematic.getCompound("Biomes").orElse(null);
            if (biomes == null) {
                return;
            }
            palette = biomes.getCompoundOrEmpty("Palette");
            data = biomes.getByteArray("Data").orElse(new byte[0]);
            flat = false;
        } else {
            if (!schematic.contains("BiomeData")) {
                return;
            }
            palette = schematic.getCompoundOrEmpty("BiomePalette");
            data = schematic.getByteArray("BiomeData").orElse(new byte[0]);
            flat = true;
        }
        Map<Integer, Holder<Biome>> byId = new HashMap<>();
        for (String key : palette.keySet()) {
            Identifier id = Identifier.tryParse(key);
            if (id != null) {
                registry.get(ResourceKey.create(Registries.BIOME, id)).ifPresent(h -> byId.put(palette.getIntOr(key, 0), h));
            }
        }
        clipboard.enableBiomes(registry);
        long total = flat ? (long) width * (long) length : (long) width * (long) height * (long) length;
        VarIntReader reader = new VarIntReader(data);
        long layer = (long) width * (long) length;
        for (long index = 0; index < total; index++) {
            int value = reader.next();
            Holder<Biome> biome = byId.get(value);
            if (biome == null) {
                continue;
            }
            if (flat) {
                int z = (int) (index / width);
                int x = (int) (index % width);
                for (int y = 0; y < height; y++) {
                    clipboard.setBiome(min.x() + x, min.y() + y, min.z() + z, biome);
                }
            } else {
                int y = (int) (index / layer);
                long rest = index % layer;
                clipboard.setBiome(min.x() + (int) (rest % width), min.y() + y, min.z() + (int) (rest / width), biome);
            }
        }
    }

    private static void readEntities(CompoundTag schematic, int version, int dataVersion, Clipboard clipboard, BlockVec base, ImportReport.Builder report) throws IOException {
        List<CompoundTag> bodies = new ArrayList<>();
        List<Vec3> positions = new ArrayList<>();
        for (Tag tag : schematic.getListOrEmpty("Entities")) {
            if (!(tag instanceof CompoundTag entity)) {
                report.invalidEntity("entry is not a compound");
                continue;
            }
            ListTag pos = entity.getListOrEmpty("Pos");
            if (pos.size() != 3) {
                report.invalidEntity(entity.getStringOr("Id", "?") + " has no position");
                continue;
            }
            Vec3 at = new Vec3(base.x() + pos.getDoubleOr(0, 0), base.y() + pos.getDoubleOr(1, 0), base.z() + pos.getDoubleOr(2, 0));
            BlockVec block = at.toBlock();
            if (!clipboard.inBounds(block.x(), block.y(), block.z())) {
                report.invalidEntity(entity.getStringOr("Id", "?") + " outside bounds at " + at);
                continue;
            }
            CompoundTag body = version >= 3 ? entity.getCompoundOrEmpty("Data").copy() : entity.copy();
            String id = entity.getStringOr("Id", body.getStringOr("id", ""));
            body.remove("Id");
            body.remove("Pos");
            body.putString("id", id);
            bodies.add(body);
            positions.add(at);
        }
        List<CompoundTag> fixed = Fixer.entities(bodies, dataVersion);
        for (int i = 0; i < fixed.size(); i++) {
            CompoundTag body = fixed.get(i);
            ListTag rotation = body.getListOrEmpty("Rotation");
            float yaw = rotation.size() == 2 ? rotation.getFloatOr(0, 0) : 0;
            float pitch = rotation.size() == 2 ? rotation.getFloatOr(1, 0) : 0;
            clipboard.addEntity(new EntityData(positions.get(i), yaw, pitch, body));
        }
    }

    public static CompoundTag write(Clipboard clipboard, int version, boolean brokenEntities) {
        BlockVec min = clipboard.min();
        BlockVec size = clipboard.dimensions();
        if (size.x() > 0xFFFF || size.y() > 0xFFFF || size.z() > 0xFFFF) {
            throw new IllegalArgumentException("Region too large for a .schematic");
        }
        BlockVec origin = clipboard.origin();
        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", version);
        schematic.putInt("DataVersion", Fixer.current());
        CompoundTag metadata = new CompoundTag();
        metadata.putLong("Date", System.currentTimeMillis());
        schematic.putShort("Width", (short) size.x());
        schematic.putShort("Height", (short) size.y());
        schematic.putShort("Length", (short) size.z());
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream((int) Math.min((long) size.x() * size.y() * size.z(), 1 << 24));
        ListTag tiles = new ListTag();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    int wx = min.x() + x;
                    int wy = min.y() + y;
                    int wz = min.z() + z;
                    BlockState state = clipboard.get(wx, wy, wz);
                    String key = BlockStateParser.serialize(state);
                    Integer id = palette.get(key);
                    if (id == null) {
                        id = palette.size();
                        palette.put(key, id);
                    }
                    varint(data, id);
                    CompoundTag tile = clipboard.tile(wx, wy, wz);
                    String tileId = tile == null ? null : tile.contains("id") ? tile.getStringOr("id", "") : blockEntityId(state);
                    if (tile != null && tileId != null) {
                        CompoundTag entry = new CompoundTag();
                        CompoundTag body = tile.copy();
                        body.remove("id");
                        if (version >= 3) {
                            entry.put("Data", body);
                        } else {
                            body.remove("Id");
                            body.remove("Pos");
                            entry.merge(body);
                        }
                        entry.putString("Id", tileId);
                        entry.putIntArray("Pos", new int[] {x, y, z});
                        tiles.add(entry);
                    }
                }
            }
        }
        CompoundTag paletteTag = new CompoundTag();
        palette.forEach(paletteTag::putInt);
        if (version >= 3) {
            CompoundTag worldEdit = new CompoundTag();
            worldEdit.putString("Version", Sched.plugin().getPluginMeta().getVersion());
            worldEdit.putString("EditingPlatform", "kekasyncworldedit");
            worldEdit.putIntArray("Origin", new int[] {origin.x(), origin.y(), origin.z()});
            metadata.put("WorldEdit", worldEdit);
            BlockVec offset = min.subtract(origin);
            schematic.putIntArray("Offset", new int[] {offset.x(), offset.y(), offset.z()});
            CompoundTag blocks = new CompoundTag();
            blocks.put("Palette", paletteTag);
            blocks.putByteArray("Data", data.toByteArray());
            blocks.put("BlockEntities", tiles);
            schematic.put("Blocks", blocks);
        } else {
            BlockVec offset = min.subtract(origin);
            metadata.putInt("WEOffsetX", offset.x());
            metadata.putInt("WEOffsetY", offset.y());
            metadata.putInt("WEOffsetZ", offset.z());
            schematic.putIntArray("Offset", new int[] {min.x(), min.y(), min.z()});
            schematic.putInt("PaletteMax", palette.size());
            schematic.put("Palette", paletteTag);
            schematic.putByteArray("BlockData", data.toByteArray());
            schematic.put("BlockEntities", tiles);
        }
        schematic.put("Metadata", metadata);
        if (clipboard.hasBiomes()) {
            writeBiomes(clipboard, schematic, version);
        }
        if (!clipboard.entities().isEmpty()) {
            schematic.put("Entities", writeEntities(clipboard, version, version >= 3 || brokenEntities ? clipboard.min() : BlockVec.ZERO));
        }
        if (version >= 3) {
            CompoundTag root = new CompoundTag();
            root.put("Schematic", schematic);
            return root;
        }
        return schematic;
    }

    private static final Map<Block, Optional<String>> BLOCK_ENTITY_IDS = new ConcurrentHashMap<>();

    static String blockEntityId(BlockState state) {
        return BLOCK_ENTITY_IDS.computeIfAbsent(state.getBlock(), block -> {
            for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                if (type.isValid(state)) {
                    return Optional.of(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    private static void writeBiomes(Clipboard clipboard, CompoundTag schematic, int version) {
        BlockVec min = clipboard.min();
        BlockVec size = clipboard.dimensions();
        LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int layers = version >= 3 ? size.y() : 1;
        for (int y = 0; y < layers; y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    Holder<Biome> biome = clipboard.biome(min.x() + x, min.y() + y, min.z() + z);
                    String key = biome == null ? "minecraft:plains" : biome.unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
                    Integer id = palette.get(key);
                    if (id == null) {
                        id = palette.size();
                        palette.put(key, id);
                    }
                    varint(data, id);
                }
            }
        }
        CompoundTag paletteTag = new CompoundTag();
        palette.forEach(paletteTag::putInt);
        if (version >= 3) {
            CompoundTag biomes = new CompoundTag();
            biomes.put("Palette", paletteTag);
            biomes.putByteArray("Data", data.toByteArray());
            schematic.put("Biomes", biomes);
        } else {
            schematic.putInt("BiomePaletteMax", palette.size());
            schematic.put("BiomePalette", paletteTag);
            schematic.putByteArray("BiomeData", data.toByteArray());
        }
    }

    private static ListTag writeEntities(Clipboard clipboard, int version, BlockVec base) {
        ListTag list = new ListTag();
        for (EntityData data : clipboard.entities()) {
            CompoundTag body = data.tag().copy();
            String id = body.getStringOr("id", "");
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(data.position().x() - base.x()));
            pos.add(DoubleTag.valueOf(data.position().y() - base.y()));
            pos.add(DoubleTag.valueOf(data.position().z() - base.z()));
            ListTag rotation = new ListTag();
            rotation.add(FloatTag.valueOf(data.yaw()));
            rotation.add(FloatTag.valueOf(data.pitch()));
            body.put("Rotation", rotation);
            CompoundTag entry;
            if (version >= 3) {
                body.remove("id");
                entry = new CompoundTag();
                entry.putString("Id", id);
                entry.put("Pos", pos);
                entry.put("Data", body);
            } else {
                entry = body;
                entry.remove("id");
                entry.putString("Id", id);
                entry.put("Pos", pos);
            }
            list.add(entry);
        }
        return list;
    }

    static void varint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & -128) != 0) {
            out.write(v & 127 | 128);
            v >>>= 7;
        }
        out.write(v);
    }
}
