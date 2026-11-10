package teacommontea.kawe.schematic;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class LegacyCompat {

    private static final String[] BED_COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final String[] SKULLS = {"skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head"};
    private static final String[] WALL_SKULLS = {"skeleton_wall_skull", "wither_skeleton_wall_skull", "zombie_wall_head", "player_wall_head", "creeper_wall_head",
            "dragon_wall_head"};

    private static final Block RED_BED = block("minecraft:red_bed");
    private static final Block WHITE_BANNER = block("minecraft:white_banner");
    private static final Block WHITE_WALL_BANNER = block("minecraft:white_wall_banner");

    private LegacyCompat() {
    }

    record Result(BlockState state, CompoundTag tile) {
    }

    static boolean obsolete(String id) {
        return switch (id) {
            case "minecraft:bed", "Bed", "minecraft:noteblock", "Music", "minecraft:flower_pot", "FlowerPot" -> true;
            default -> false;
        };
    }

    static Result apply(BlockState state, CompoundTag tile) {
        Block block = state.getBlock();
        if (block == Blocks.NOTE_BLOCK && tile.contains("note")) {
            int note = Math.clamp(tile.getByteOr("note", (byte) 0), 0, 24);
            return new Result(state.setValue(BlockStateProperties.NOTE, note), null);
        }
        if (block == Blocks.FLOWER_POT) {
            return new Result(flowerPot(tile), null);
        }
        if (block == RED_BED && tile.contains("color")) {
            int color = tile.getIntOr("color", 14);
            Block bed = color >= 0 && color < 16 ? block("minecraft:" + BED_COLORS[color] + "_bed") : null;
            if (bed == null) {
                return new Result(state, tile);
            }
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BedPart part = state.getValue(BlockStateProperties.BED_PART);
            CompoundTag rest = tile.copy();
            rest.remove("color");
            return new Result(bed.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing).setValue(BlockStateProperties.BED_PART, part)
                    .setValue(BlockStateProperties.OCCUPIED, false), rest);
        }
        boolean wallSkull = block == Blocks.SKELETON_WALL_SKULL;
        if ((block == Blocks.SKELETON_SKULL || wallSkull) && tile.contains("SkullType")) {
            int type = tile.getByteOr("SkullType", (byte) 0);
            if (type < 0 || type >= SKULLS.length) {
                return new Result(state, tile);
            }
            Block skull = block("minecraft:" + (wallSkull ? WALL_SKULLS[type] : SKULLS[type]));
            if (skull == null) {
                return new Result(state, tile);
            }
            BlockState out = skull.defaultBlockState();
            if (wallSkull) {
                out = out.setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
            } else if (tile.contains("Rot")) {
                out = out.setValue(BlockStateProperties.ROTATION_16, Math.floorMod(tile.getByteOr("Rot", (byte) 0), 16));
            }
            CompoundTag rest = tile.copy();
            rest.remove("SkullType");
            rest.remove("Rot");
            return new Result(out, rest);
        }
        boolean wallBanner = block == WHITE_WALL_BANNER;
        if ((block == WHITE_BANNER || wallBanner) && tile.contains("Base")) {
            int base = tile.getIntOr("Base", 15);
            Block banner = base >= 0 && base < 16 ? block("minecraft:" + BED_COLORS[15 - base] + (wallBanner ? "_wall_banner" : "_banner")) : null;
            if (banner == null) {
                return new Result(state, tile);
            }
            BlockState out = wallBanner
                    ? banner.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(BlockStateProperties.HORIZONTAL_FACING))
                    : banner.defaultBlockState().setValue(BlockStateProperties.ROTATION_16, state.getValue(BlockStateProperties.ROTATION_16));
            return new Result(out, tile);
        }
        return new Result(state, tile);
    }

    private static BlockState flowerPot(CompoundTag tile) {
        BlockState empty = Blocks.FLOWER_POT.defaultBlockState();
        Tag item = tile.get("Item");
        if (item == null) {
            return empty;
        }
        String id = tile.getStringOr("Item", "");
        int data = tile.getIntOr("Data", 0);
        if (id.isEmpty()) {
            return empty;
        }
        int legacy = switch (id) {
            case "minecraft:red_flower" -> 38;
            case "minecraft:yellow_flower" -> 37;
            case "minecraft:sapling" -> 6;
            case "minecraft:deadbush", "minecraft:tallgrass" -> 31;
            default -> 0;
        };
        String planted = null;
        if (legacy == 0 && id.startsWith("minecraft:")) {
            planted = id.substring("minecraft:".length());
        } else if (legacy != 0) {
            BlockState plant = Legacy.state(legacy, data);
            if (plant != null) {
                planted = BuiltInRegistries.BLOCK.getKey(plant.getBlock()).getPath();
            }
        }
        Block potted = planted == null ? null : block("minecraft:potted_" + planted);
        return potted == null ? empty : potted.defaultBlockState();
    }

    private static Block block(String id) {
        Identifier key = Identifier.tryParse(id);
        return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
    }
}
