package teacommontea.kawe.command;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import org.bukkit.block.BlockFace;
import teacommontea.kawe.Sched;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.parse.Patterns;
import teacommontea.kawe.session.Actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class Types {

    private Types() {
    }

    public static final ArgType<Integer> INT = new ArgType<>() {
        @Override
        public boolean accepts(String raw) {
            return integer(raw);
        }

        @Override
        public Integer parse(String raw, CmdContext context) throws InputParseException {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw InputParseException.of("kawe.error.invalid-number.matches", raw);
            }
        }
    };

    public static final ArgType<Double> DOUBLE = new ArgType<>() {
        @Override
        public boolean accepts(String raw) {
            return numeric(raw);
        }

        @Override
        public Double parse(String raw, CmdContext context) throws InputParseException {
            try {
                double value = Double.parseDouble(raw);
                if (!Double.isFinite(value)) {
                    throw new NumberFormatException(raw);
                }
                return value;
            } catch (NumberFormatException e) {
                throw InputParseException.of("kawe.error.invalid-number.matches", raw);
            }
        }
    };

    public static final ArgType<List<Double>> RADII = new ArgType<>() {
        @Override
        public boolean accepts(String raw) {
            for (String part : raw.split(",", -1)) {
                if (!numeric(part.trim())) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public List<Double> parse(String raw, CmdContext context) throws InputParseException {
            List<Double> out = new ArrayList<>();
            for (String part : raw.split(",", -1)) {
                out.add(DOUBLE.parse(part.trim(), context));
            }
            return out;
        }
    };

    public static final ArgType<String> WORD = (raw, context) -> raw;

    public static final ArgType<String> TEXT = new ArgType<>() {
        @Override
        public String parse(String raw, CmdContext context) {
            return raw;
        }

        @Override
        public boolean greedy() {
            return true;
        }
    };

    public static final ArgType<Boolean> BOOL = new ArgType<>() {
        @Override
        public boolean accepts(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on", "y", "1", "allow", "false", "no", "off", "n", "0", "deny" -> true;
                default -> false;
            };
        }

        @Override
        public Boolean parse(String raw, CmdContext context) throws InputParseException {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "true", "yes", "on", "y", "1", "allow" -> true;
                case "false", "no", "off", "n", "0", "deny" -> false;
                default -> throw InputParseException.of("kawe.error.invalid-boolean", raw);
            };
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return filter(Stream.of("true", "false"), partial);
        }
    };

    public static final ArgType<Pattern> PATTERN = new ArgType<>() {
        @Override
        public Pattern parse(String raw, CmdContext context) throws InputParseException {
            return Patterns.parse(raw, context.parseContext());
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return blockSuggestions(partial, Patterns.aliases());
        }
    };

    public static final ArgType<Mask> MASK = new ArgType<>() {
        @Override
        public Mask parse(String raw, CmdContext context) throws InputParseException {
            return Masks.parse(raw, context.parseContext());
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return blockSuggestions(partial, Masks.aliases());
        }
    };

    public static final ArgType<BlockVec> VECTOR = new ArgType<>() {
        @Override
        public BlockVec parse(String raw, CmdContext context) throws InputParseException {
            String[] parts = raw.split(",", -1);
            if (parts.length != 3) {
                throw InputParseException.of("kawe.error.parser.clipboard.missing-coordinates");
            }
            int[] out = new int[3];
            for (int i = 0; i < 3; i++) {
                String part = parts[i].trim();
                double value;
                try {
                    value = Double.parseDouble(part);
                } catch (NumberFormatException e) {
                    throw InputParseException.of("kawe.error.invalid-number.matches", part.isEmpty() ? raw : part);
                }
                double floored = Math.floor(value);
                if (!Double.isFinite(floored) || floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
                    throw InputParseException.of("kawe.error.invalid-number.matches", part);
                }
                out[i] = (int) floored;
            }
            return new BlockVec(out[0], out[1], out[2]);
        }
    };

    public static final ArgType<List<BlockVec>> DIRECTIONS = new ArgType<>() {
        @Override
        public List<BlockVec> parse(String raw, CmdContext context) throws InputParseException {
            List<BlockVec> out = new ArrayList<>();
            for (String part : raw.split(",", -1)) {
                if (part.isBlank()) {
                    throw InputParseException.of("kawe.error.unknown-direction", raw);
                }
                out.add(direction(part, context.actor()));
            }
            return out;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return filter(Stream.of("me", "up", "down", "north", "east", "south", "west", "forward", "back", "left", "right"), partial);
        }
    };

    public static final ArgType<BlockVec> DIRECTION = new ArgType<>() {
        @Override
        public BlockVec parse(String raw, CmdContext context) throws InputParseException {
            return direction(raw, context.actor());
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return DIRECTIONS.suggest(partial, context);
        }
    };

    public static BlockVec direction(String raw, Actor actor) throws InputParseException {
        String dir = raw.trim().toLowerCase(Locale.ROOT);
        return switch (dir) {
            case "u", "up" -> BlockVec.UNIT_Y;
            case "d", "down" -> BlockVec.UNIT_MINUS_Y;
            case "n", "north" -> BlockVec.UNIT_MINUS_Z;
            case "s", "south" -> BlockVec.UNIT_Z;
            case "e", "east" -> BlockVec.UNIT_X;
            case "w", "west" -> BlockVec.UNIT_MINUS_X;
            case "m", "me", "f", "forward", "aim" -> aim(actor, true);
            case "b", "back" -> aim(actor, true).negate();
            case "l", "left" -> rotate(aim(actor, false), true);
            case "r", "right" -> rotate(aim(actor, false), false);
            default -> throw InputParseException.of("kawe.error.unknown-direction", raw);
        };
    }

    private static BlockVec aim(Actor actor, boolean vertical) throws InputParseException {
        if (!actor.isPlayer()) {
            throw InputParseException.of("kawe.error.parser.player-only", "me");
        }
        float pitch = actor.pitch();
        if (vertical && pitch > 67.5) {
            return BlockVec.UNIT_MINUS_Y;
        }
        if (vertical && pitch < -67.5) {
            return BlockVec.UNIT_Y;
        }
        BlockFace face = Actor.yawFace(actor.yaw());
        return new BlockVec(face.getModX(), 0, face.getModZ());
    }

    private static BlockVec rotate(BlockVec dir, boolean left) {
        return left ? new BlockVec(dir.z(), 0, -dir.x()) : new BlockVec(-dir.z(), 0, dir.x());
    }

    static boolean integer(String raw) {
        try {
            Integer.parseInt(raw.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static volatile boolean suggestionFailureLogged;

    static boolean numeric(String raw) {
        try {
            return Double.isFinite(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static List<String> filter(Stream<String> options, String partial) {
        String lower = partial.toLowerCase(Locale.ROOT);
        return options.filter(s -> s.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    public static List<String> blockSuggestions(String partial, Iterable<String> aliases) {
        int cut = -1;
        for (char separator : new char[]{',', '&', '%', '[', ' '}) {
            cut = Math.max(cut, partial.lastIndexOf(separator));
        }
        String prefix = cut >= 0 ? partial.substring(0, cut + 1) : "";
        String current = cut >= 0 ? partial.substring(cut + 1) : partial;
        String lower = current.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String alias : aliases) {
            if (alias.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(prefix + alias);
            }
        }
        if (!(current.startsWith("#") || current.startsWith("!") || current.startsWith("^") || current.startsWith("*"))) {
            try {
                SuggestionsBuilder builder = new SuggestionsBuilder(current, 0);
                for (Suggestion suggestion : BlockStateParser.fillSuggestions(BuiltInRegistries.BLOCK, builder, false, true).join().getList()) {
                    out.add(prefix + suggestion.apply(current));
                }
            } catch (RuntimeException e) {
                if (!suggestionFailureLogged) {
                    suggestionFailureLogged = true;
                    Sched.plugin().getLogger().log(Level.WARNING, "Block suggestions failed for '" + current + "'", e);
                }
            }
        }
        return out.size() > 200 ? out.subList(0, 200) : out;
    }
}
