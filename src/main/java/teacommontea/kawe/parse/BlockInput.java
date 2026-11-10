package teacommontea.kawe.parse;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.IncompleteRegionException;
import teacommontea.kawe.session.Actor;

import java.util.Locale;
import java.util.Map;

public final class BlockInput {

    private BlockInput() {
    }

    public record Parsed(BlockState state, Map<Property<?>, Comparable<?>> properties, CompoundTag nbt) {
    }

    public static Parsed parse(String input, ParseContext context) throws InputParseException {
        String text = input.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (context != null) {
            Parsed special = special(lower, context);
            if (special != null) {
                return special;
            }
        }
        try {
            BlockStateParser.BlockResult result = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, true);
            return new Parsed(result.blockState(), result.properties(), result.nbt());
        } catch (CommandSyntaxException e) {
            throw InputParseException.of("kawe.error.unknown-block", text);
        }
    }

    public static BlockState state(String input, ParseContext context) throws InputParseException {
        return parse(input, context).state();
    }

    private static Parsed special(String lower, ParseContext context) throws InputParseException {
        switch (lower) {
            case "hand", "offhand" -> {
                Actor.HandBlock held = lower.equals("hand") ? context.actor().hand() : context.actor().offhand();
                if (held == null) {
                    throw InputParseException.of("kawe.error.not-a-block");
                }
                return new Parsed(held.state(), Map.of(), held.nbt() == null ? null : held.nbt().copy());
            }
            case "pos1" -> {
                try {
                    BlockVec pos = context.session().selector(context.world().bukkit()).primary();
                    if (context.extent() instanceof Edit edit) {
                        edit.captureTiles();
                    }
                    return new Parsed(context.extent().get(pos.x(), pos.y(), pos.z()), Map.of(), context.extent().tile(pos.x(), pos.y(), pos.z()));
                } catch (IncompleteRegionException e) {
                    throw InputParseException.of("kawe.error.incomplete-region");
                }
            }
            default -> {
                return null;
            }
        }
    }

    public static String name(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    public static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }
}
