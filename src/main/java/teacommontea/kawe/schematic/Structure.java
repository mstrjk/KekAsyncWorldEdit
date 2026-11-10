package teacommontea.kawe.schematic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Structure {

    private Structure() {
    }

    public static boolean isStructure(CompoundTag root) {
        return root.contains("size") && (root.contains("palette") || root.contains("palettes")) && root.contains("blocks");
    }

    public static Clipboard read(CompoundTag root) throws IOException {
        int dataVersion = root.getIntOr("DataVersion", 1343);
        ListTag size = root.getListOrEmpty("size");
        if (size.size() != 3) {
            throw new IOException("Invalid structure size");
        }
        int sx = size.getIntOr(0, 0);
        int sy = size.getIntOr(1, 0);
        int sz = size.getIntOr(2, 0);
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new IOException("Empty structure");
        }
        Clipboard clipboard = new Clipboard(new CuboidRegion(BlockVec.ZERO, new BlockVec(sx - 1, sy - 1, sz - 1)), BlockVec.ZERO);
        ImportReport.Builder report = new ImportReport.Builder();
        List<String> unknownNames = new ArrayList<>();
        ListTag paletteTag = root.contains("palette") ? root.getListOrEmpty("palette") : root.getListOrEmpty("palettes").getListOrEmpty(0);
        List<BlockState> palette = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Tag tag : paletteTag) {
            names.add(tag instanceof CompoundTag entry ? stateString(entry) : "");
        }
        report.dataVersion(dataVersion, Fixer.current());
        List<Fixer.Resolved> fixed = Fixer.states(names, dataVersion);
        List<String> approximatedNames = new ArrayList<>();
        for (int k = 0; k < names.size(); k++) {
            BlockState state = fixed.get(k).state();
            unknownNames.add(state == null ? names.get(k) : null);
            approximatedNames.add(state != null && fixed.get(k).approximated() ? names.get(k) : null);
            palette.add(state == null ? Blocks.AIR.defaultBlockState() : state);
        }
        List<Fixer.Tile> pendingTiles = new ArrayList<>();
        List<BlockVec> tilePositions = new ArrayList<>();
        for (Tag tag : root.getListOrEmpty("blocks")) {
            if (!(tag instanceof CompoundTag block)) {
                continue;
            }
            ListTag pos = block.getListOrEmpty("pos");
            int index = block.getIntOr("state", 0);
            if (pos.size() != 3) {
                report.invalidBlockEntity("block entry has no position");
                continue;
            }
            if (index < 0 || index >= palette.size()) {
                throw new IOException("Structure palette has no entry for index " + index);
            }
            if (unknownNames.get(index) != null) {
                report.unknownBlock(unknownNames.get(index));
            } else if (approximatedNames.get(index) != null) {
                report.fallbackMapping(approximatedNames.get(index));
            }
            BlockState state = palette.get(index);
            BlockVec at = new BlockVec(pos.getIntOr(0, 0), pos.getIntOr(1, 0), pos.getIntOr(2, 0));
            if (!clipboard.set(at.x(), at.y(), at.z(), state)) {
                report.invalidBlockEntity("block outside bounds at " + at);
                continue;
            }
            CompoundTag nbt = block.getCompound("nbt").orElse(null);
            if (nbt == null) {
                continue;
            }
            if (!state.hasBlockEntity()) {
                report.invalidBlockEntity(nbt.getStringOr("id", "?") + " on a block without a block entity");
                continue;
            }
            pendingTiles.add(new Fixer.Tile(nbt.copy(), names.get(index)));
            tilePositions.add(at);
        }
        List<CompoundTag> fixedTiles = Fixer.blockEntities(pendingTiles, dataVersion);
        for (int i = 0; i < fixedTiles.size(); i++) {
            BlockVec at = tilePositions.get(i);
            clipboard.set(at.x(), at.y(), at.z(), clipboard.get(at.x(), at.y(), at.z()), fixedTiles.get(i));
        }
        List<CompoundTag> entityBodies = new ArrayList<>();
        List<Vec3> entityPositions = new ArrayList<>();
        for (Tag tag : root.getListOrEmpty("entities")) {
            if (!(tag instanceof CompoundTag entity)) {
                report.invalidEntity("entry is not a compound");
                continue;
            }
            ListTag pos = entity.getListOrEmpty("pos");
            CompoundTag nbt = entity.getCompound("nbt").orElse(null);
            if (pos.size() != 3 || nbt == null) {
                report.invalidEntity("entity entry missing position or data");
                continue;
            }
            Vec3 at = new Vec3(pos.getDoubleOr(0, 0), pos.getDoubleOr(1, 0), pos.getDoubleOr(2, 0));
            BlockVec block = at.toBlock();
            if (!clipboard.inBounds(block.x(), block.y(), block.z())) {
                report.invalidEntity(nbt.getStringOr("id", "?") + " outside bounds at " + at);
                continue;
            }
            entityBodies.add(nbt.copy());
            entityPositions.add(at);
        }
        List<CompoundTag> fixedEntities = Fixer.entities(entityBodies, dataVersion);
        for (int i = 0; i < fixedEntities.size(); i++) {
            CompoundTag nbt = fixedEntities.get(i);
            ListTag rotation = nbt.getListOrEmpty("Rotation");
            clipboard.addEntity(new EntityData(entityPositions.get(i), rotation.getFloatOr(0, 0), rotation.getFloatOr(1, 0), nbt));
        }
        clipboard.report(report.build());
        return clipboard;
    }

    private static String stateString(CompoundTag entry) {
        return Fixer.fromNbt(entry);
    }

    public static CompoundTag write(Clipboard clipboard) {
        BlockVec min = clipboard.min();
        BlockVec size = clipboard.dimensions();
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", Fixer.current());
        root.put("size", ints(size.x(), size.y(), size.z()));
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        ListTag blocks = new ListTag();
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    BlockState state = clipboard.get(min.x() + x, min.y() + y, min.z() + z);
                    if (state.getBlock() == Blocks.STRUCTURE_VOID) {
                        continue;
                    }
                    Integer index = palette.get(state);
                    if (index == null) {
                        index = palette.size();
                        palette.put(state, index);
                    }
                    CompoundTag block = new CompoundTag();
                    block.put("pos", ints(x, y, z));
                    block.putInt("state", index);
                    CompoundTag tile = clipboard.tile(min.x() + x, min.y() + y, min.z() + z);
                    if (tile != null) {
                        String id = tile.contains("id") ? tile.getStringOr("id", "") : Sponge.blockEntityId(state);
                        if (id != null) {
                            tile.putString("id", id);
                            block.put("nbt", tile);
                        }
                    }
                    blocks.add(block);
                }
            }
        }
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette.keySet()) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        root.put("palette", paletteTag);
        root.put("blocks", blocks);
        ListTag entities = new ListTag();
        for (EntityData data : clipboard.entities()) {
            CompoundTag entry = new CompoundTag();
            Vec3 rel = data.position().subtract(min.toVec3());
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(rel.x()));
            pos.add(DoubleTag.valueOf(rel.y()));
            pos.add(DoubleTag.valueOf(rel.z()));
            entry.put("pos", pos);
            entry.put("blockPos", ints((int) Math.floor(rel.x()), (int) Math.floor(rel.y()), (int) Math.floor(rel.z())));
            entry.put("nbt", data.tag().copy());
            entities.add(entry);
        }
        root.put("entities", entities);
        root.putString("author", "KekAsyncWorldEdit");
        return root;
    }

    private static ListTag ints(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }
}
