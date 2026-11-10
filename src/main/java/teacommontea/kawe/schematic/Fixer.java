package teacommontea.kawe.schematic;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class Fixer {

    public record Resolved(BlockState state, boolean approximated) {
    }

    public record Tile(CompoundTag tag, String blockName) {
    }

    private Fixer() {
    }

    public static int current() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    public static boolean newer(int from) {
        return from > current();
    }

    private static CompoundTag structure(int from) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", from);
        root.put("size", ints(1, 1, 1));
        root.put("palette", new ListTag());
        root.put("blocks", new ListTag());
        root.put("entities", new ListTag());
        return root;
    }

    private static CompoundTag upgrade(CompoundTag structure, int from) {
        return DataFixTypes.STRUCTURE.update(DataFixers.getDataFixer(), structure, from, current());
    }

    private static ListTag ints(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }

    public static List<Resolved> states(List<String> names, int from) throws IOException {
        List<String> fixed = names;
        if (from < current() && !names.isEmpty()) {
            CompoundTag root = structure(from);
            ListTag palette = root.getListOrEmpty("palette");
            for (String name : names) {
                palette.add(toNbt(name));
            }
            ListTag upgraded = upgrade(root, from).getListOrEmpty("palette");
            if (upgraded.size() != names.size()) {
                throw new IOException("Data upgrade changed the palette size from " + names.size() + " to " + upgraded.size());
            }
            fixed = new ArrayList<>(names.size());
            for (Tag tag : upgraded) {
                fixed.add(tag instanceof CompoundTag compound ? fromNbt(compound) : "");
            }
        }
        List<Resolved> out = new ArrayList<>(fixed.size());
        for (String name : fixed) {
            out.add(parse(name));
        }
        return out;
    }

    public static List<CompoundTag> blockEntities(List<Tile> tiles, int from) throws IOException {
        if (from >= current() || tiles.isEmpty()) {
            List<CompoundTag> same = new ArrayList<>(tiles.size());
            tiles.forEach(t -> same.add(t.tag()));
            return same;
        }
        CompoundTag root = structure(from);
        ListTag palette = root.getListOrEmpty("palette");
        ListTag blocks = root.getListOrEmpty("blocks");
        for (int i = 0; i < tiles.size(); i++) {
            Tile tile = tiles.get(i);
            palette.add(toNbt(tile.blockName() == null ? "minecraft:air" : tile.blockName()));
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(i, 0, 0));
            block.putInt("state", i);
            block.put("nbt", tile.tag());
            blocks.add(block);
        }
        root.put("size", ints(tiles.size(), 1, 1));
        ListTag upgraded = upgrade(root, from).getListOrEmpty("blocks");
        if (upgraded.size() != tiles.size()) {
            throw new IOException("Data upgrade changed the block entity count from " + tiles.size() + " to " + upgraded.size());
        }
        List<CompoundTag> out = new ArrayList<>(tiles.size());
        for (int i = 0; i < upgraded.size(); i++) {
            CompoundTag nbt = upgraded.getCompoundOrEmpty(i).getCompound("nbt").orElse(null);
            if (nbt == null) {
                throw new IOException("Data upgrade dropped block entity " + i);
            }
            out.add(nbt);
        }
        return out;
    }

    public static List<CompoundTag> entities(List<CompoundTag> tags, int from) throws IOException {
        if (from >= current() || tags.isEmpty()) {
            return new ArrayList<>(tags);
        }
        CompoundTag root = structure(from);
        ListTag list = root.getListOrEmpty("entities");
        for (CompoundTag tag : tags) {
            CompoundTag entity = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(DoubleTag.valueOf(0));
            pos.add(DoubleTag.valueOf(0));
            pos.add(DoubleTag.valueOf(0));
            entity.put("pos", pos);
            entity.put("blockPos", ints(0, 0, 0));
            entity.put("nbt", tag);
            list.add(entity);
        }
        ListTag upgraded = upgrade(root, from).getListOrEmpty("entities");
        if (upgraded.size() != tags.size()) {
            throw new IOException("Data upgrade changed the entity count from " + tags.size() + " to " + upgraded.size());
        }
        List<CompoundTag> out = new ArrayList<>(tags.size());
        for (int i = 0; i < upgraded.size(); i++) {
            CompoundTag nbt = upgraded.getCompoundOrEmpty(i).getCompound("nbt").orElse(null);
            if (nbt == null) {
                throw new IOException("Data upgrade dropped entity " + i);
            }
            out.add(nbt);
        }
        return out;
    }

    private static Resolved parse(String input) {
        if (input.isEmpty()) {
            return new Resolved(null, false);
        }
        try {
            return new Resolved(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, input, false).blockState(), false);
        } catch (CommandSyntaxException e) {
            int open = input.indexOf('[');
            if (open > 0 && input.endsWith("]")) {
                try {
                    return new Resolved(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, input.substring(0, open), false).blockState(), true);
                } catch (CommandSyntaxException ignored) {
                    return new Resolved(null, false);
                }
            }
            return new Resolved(null, false);
        }
    }

    static CompoundTag toNbt(String text) {
        CompoundTag tag = new CompoundTag();
        int open = text.indexOf('[');
        boolean closed = open >= 0 && text.endsWith("]");
        String name = open >= 0 && closed ? text.substring(0, open) : text;
        tag.putString("Name", name.contains(":") ? name : "minecraft:" + name);
        if (closed) {
            CompoundTag properties = new CompoundTag();
            for (String pair : text.substring(open + 1, text.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    properties.putString(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
                }
            }
            tag.put("Properties", properties);
        }
        return tag;
    }

    static String fromNbt(CompoundTag tag) {
        StringBuilder out = new StringBuilder(tag.contains("id") ? tag.getStringOr("id", "") : tag.getStringOr("Name", ""));
        CompoundTag properties = tag.contains("properties") ? tag.getCompoundOrEmpty("properties") : tag.getCompoundOrEmpty("Properties");
        if (!properties.isEmpty()) {
            out.append('[');
            boolean first = true;
            for (String key : properties.keySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(key).append('=').append(properties.getStringOr(key, ""));
            }
            out.append(']');
        }
        return out.toString();
    }
}
