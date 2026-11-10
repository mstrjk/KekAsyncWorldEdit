package teacommontea.kawe.schematic;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.engine.EntityData;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.CuboidRegion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class McEdit {

    private static final int LEGACY_DATA = 1343;
    private static final int PRE_FLATTENING_IDS = 99;

    private McEdit() {
    }

    public static boolean isMcEdit(CompoundTag root) {
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        return schematic.contains("Blocks") && schematic.contains("Data") && !schematic.contains("Version");
    }

    private static int legacyVersion(CompoundTag tag) {
        return tag.getStringOr("id", "").contains(":") ? LEGACY_DATA : PRE_FLATTENING_IDS;
    }

    private static BlockVec locate(BlockVec pos, BlockVec min, int width, int height, int length) {
        boolean relative = pos.x() >= 0 && pos.x() < width && pos.y() >= 0 && pos.y() < height && pos.z() >= 0 && pos.z() < length;
        if (relative) {
            return min.add(pos);
        }
        BlockVec local = pos.subtract(min);
        boolean absolute = local.x() >= 0 && local.x() < width && local.y() >= 0 && local.y() < height && local.z() >= 0 && local.z() < length;
        return absolute ? pos : null;
    }

    private static Vec3 locate(Vec3 pos, BlockVec min, int width, int height, int length) {
        if (inside(pos.x() - min.x(), pos.y() - min.y(), pos.z() - min.z(), width, height, length)) {
            return pos;
        }
        if (inside(pos.x(), pos.y(), pos.z(), width, height, length)) {
            return pos.add(min.x(), min.y(), min.z());
        }
        return null;
    }

    private static boolean inside(double x, double y, double z, int width, int height, int length) {
        return x >= 0 && x <= width && y >= 0 && y <= height && z >= 0 && z <= length;
    }

    public static Clipboard read(CompoundTag root) throws IOException {
        CompoundTag schematic = root.getCompound("Schematic").orElse(root);
        int width = schematic.getShortOr("Width", (short) 0) & 0xFFFF;
        int height = schematic.getShortOr("Height", (short) 0) & 0xFFFF;
        int length = schematic.getShortOr("Length", (short) 0) & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IOException("Empty schematic");
        }
        BlockVec min = new BlockVec(schematic.getIntOr("WEOriginX", 0), schematic.getIntOr("WEOriginY", 0), schematic.getIntOr("WEOriginZ", 0));
        BlockVec offset = new BlockVec(schematic.getIntOr("WEOffsetX", 0), schematic.getIntOr("WEOffsetY", 0), schematic.getIntOr("WEOffsetZ", 0));
        Clipboard clipboard = new Clipboard(new CuboidRegion(min, min.add(width - 1, height - 1, length - 1)), min.subtract(offset));
        byte[] blocks = schematic.getByteArray("Blocks").orElse(new byte[0]);
        byte[] data = schematic.getByteArray("Data").orElse(new byte[0]);
        byte[] add = schematic.getByteArray("AddBlocks").orElse(null);
        BlockState air = Blocks.AIR.defaultBlockState();
        long total = (long) width * (long) height * (long) length;
        if (blocks.length < total) {
            throw new IOException("Truncated block data: " + blocks.length + " of " + total);
        }
        long layer = (long) width * (long) length;
        ImportReport.Builder report = new ImportReport.Builder();
        for (long index = 0; index < total; index++) {
            int i = (int) index;
            int id = blocks[i] & 0xFF;
            if (add != null && (i >> 1) < add.length) {
                int packed = add[i >> 1] & 0xFF;
                int nibble = (i & 1) == 0 ? packed & 0xF : (packed >>> 4) & 0xF;
                id |= nibble << 8;
            }
            int meta = i < data.length ? data[i] & 0xF : 0;
            String key = id + ":" + meta;
            BlockState state = Legacy.state(id, meta);
            if (state == null) {
                state = Legacy.state(id, 0);
                if (state == null) {
                    report.unknownBlock(key);
                    state = air;
                } else if (id != 0) {
                    report.fallbackMapping(key);
                }
            }
            int y = (int) (index / layer);
            long rest = index % layer;
            int z = (int) (rest / width);
            int x = (int) (rest % width);
            clipboard.set(min.x() + x, min.y() + y, min.z() + z, state);
        }
        Map<Integer, List<Fixer.Tile>> tilesByVersion = new HashMap<>();
        Map<Integer, List<BlockVec>> tilePositions = new HashMap<>();
        for (Tag tag : schematic.getListOrEmpty("TileEntities")) {
            if (!(tag instanceof CompoundTag tile)) {
                report.invalidBlockEntity("entry is not a compound");
                continue;
            }
            BlockVec at = locate(new BlockVec(tile.getIntOr("x", 0), tile.getIntOr("y", 0), tile.getIntOr("z", 0)), min, width, height, length);
            if (at == null) {
                report.invalidBlockEntity(tile.getStringOr("id", "?") + " outside bounds");
                continue;
            }
            LegacyCompat.Result compat = LegacyCompat.apply(clipboard.get(at.x(), at.y(), at.z()), tile);
            BlockState state = compat.state();
            clipboard.set(at.x(), at.y(), at.z(), state);
            if (compat.tile() == null) {
                continue;
            }
            tile = compat.tile();
            if (!state.hasBlockEntity()) {
                if (LegacyCompat.obsolete(tile.getStringOr("id", ""))) {
                    continue;
                }
                report.invalidBlockEntity(tile.getStringOr("id", "?") + " at " + at + " has no matching block");
                continue;
            }
            int version = legacyVersion(tile);
            tilesByVersion.computeIfAbsent(version, v -> new ArrayList<>()).add(new Fixer.Tile(tile.copy(), BlockStateParser.serialize(state)));
            tilePositions.computeIfAbsent(version, v -> new ArrayList<>()).add(at);
        }
        for (Map.Entry<Integer, List<Fixer.Tile>> group : tilesByVersion.entrySet()) {
            List<CompoundTag> fixedTiles = Fixer.blockEntities(group.getValue(), group.getKey());
            List<BlockVec> positions = tilePositions.get(group.getKey());
            for (int i = 0; i < fixedTiles.size(); i++) {
                CompoundTag body = fixedTiles.get(i);
                body.remove("x");
                body.remove("y");
                body.remove("z");
                BlockVec at = positions.get(i);
                clipboard.set(at.x(), at.y(), at.z(), clipboard.get(at.x(), at.y(), at.z()), body);
            }
        }
        Map<Integer, List<CompoundTag>> entitiesByVersion = new HashMap<>();
        Map<Integer, List<Vec3>> entityPositions = new HashMap<>();
        for (Tag tag : schematic.getListOrEmpty("Entities")) {
            if (!(tag instanceof CompoundTag entity)) {
                report.invalidEntity("entry is not a compound");
                continue;
            }
            ListTag pos = entity.getListOrEmpty("Pos");
            if (pos.size() != 3) {
                report.invalidEntity(entity.getStringOr("id", "?") + " has no position");
                continue;
            }
            Vec3 raw = new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0));
            Vec3 at = locate(raw, min, width, height, length);
            if (at == null) {
                report.invalidEntity(entity.getStringOr("id", "?") + " outside bounds at " + raw);
                continue;
            }
            int version = legacyVersion(entity);
            entitiesByVersion.computeIfAbsent(version, v -> new ArrayList<>()).add(entity.copy());
            entityPositions.computeIfAbsent(version, v -> new ArrayList<>()).add(at);
        }
        for (Map.Entry<Integer, List<CompoundTag>> group : entitiesByVersion.entrySet()) {
            List<CompoundTag> fixedEntities = Fixer.entities(group.getValue(), group.getKey());
            List<Vec3> positions = entityPositions.get(group.getKey());
            for (int i = 0; i < fixedEntities.size(); i++) {
                CompoundTag body = fixedEntities.get(i);
                ListTag rotation = body.getListOrEmpty("Rotation");
                clipboard.addEntity(new EntityData(positions.get(i), rotation.getFloatOr(0, 0), rotation.getFloatOr(1, 0), body));
            }
        }
        clipboard.report(report.build());
        return clipboard;
    }
}
