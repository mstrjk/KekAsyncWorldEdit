package teacommontea.kawe.parse;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.PatternSyntaxException;

public final class BlockMatcher {

    private BlockMatcher() {
    }

    public static BitSet all(Predicate<BlockState> test) {
        BitSet out = new BitSet();
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            if (test.test(state)) {
                out.set(Block.getId(state));
            }
        }
        return out;
    }

    public static BitSet parse(String input, ParseContext context) throws InputParseException {
        String text = input.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("hand") || lower.equals("offhand") || lower.equals("pos1")) {
            BitSet one = new BitSet();
            one.set(Block.getId(BlockInput.state(lower, context)));
            return one;
        }
        String id = text;
        String props = null;
        int open = text.endsWith("]") ? matchingOpen(text) : -1;
        if (text.endsWith("]") && open < 0) {
            throw InputParseException.of("kawe.error.parser.bad-state-format", text);
        }
        if (open >= 0 && properties(text.substring(open + 1, text.length() - 1))) {
            id = text.substring(0, open);
            props = text.substring(open + 1, text.length() - 1);
        } else if (open < 0 && text.indexOf('[') >= 0 && text.indexOf(']') < 0 && !isRegex(text.substring(0, text.indexOf('[')).toLowerCase(Locale.ROOT))) {
            throw InputParseException.of("kawe.error.parser.missing-rbracket");
        }
        List<Block> blocks = blocks(id);
        if (blocks.isEmpty()) {
            throw InputParseException.of("kawe.error.unknown-block", text);
        }
        List<Condition> conditions = props == null ? List.of() : conditions(props, blocks, text);
        BitSet out = new BitSet();
        for (Block block : blocks) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (matches(state, conditions)) {
                    out.set(Block.getId(state));
                }
            }
        }
        return out;
    }

    public static BitSet tag(String name) throws InputParseException {
        Identifier key = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
        if (key == null) {
            throw InputParseException.of("kawe.error.unknown-tag", name);
        }
        Optional<HolderSet.Named<Block>> set = BuiltInRegistries.BLOCK.get(TagKey.create(Registries.BLOCK, key));
        if (set.isEmpty()) {
            throw InputParseException.of("kawe.error.unknown-tag", name);
        }
        BitSet out = new BitSet();
        for (Holder<Block> holder : set.get()) {
            for (BlockState state : holder.value().getStateDefinition().getPossibleStates()) {
                out.set(Block.getId(state));
            }
        }
        if (out.isEmpty()) {
            throw InputParseException.of("kawe.error.empty-tag", name);
        }
        return out;
    }

    public static List<Block> tagBlocks(String name) throws InputParseException {
        Identifier key = Identifier.tryParse(name.toLowerCase(Locale.ROOT));
        if (key == null) {
            throw InputParseException.of("kawe.error.unknown-tag", name);
        }
        Optional<HolderSet.Named<Block>> set = BuiltInRegistries.BLOCK.get(TagKey.create(Registries.BLOCK, key));
        if (set.isEmpty()) {
            throw InputParseException.of("kawe.error.unknown-tag", name);
        }
        List<Block> out = new ArrayList<>();
        for (Holder<Block> holder : set.get()) {
            out.add(holder.value());
        }
        if (out.isEmpty()) {
            throw InputParseException.of("kawe.error.empty-tag", name);
        }
        return out;
    }

    public static List<Block> blocks(String id) throws InputParseException {
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("*")) {
            List<Block> all = new ArrayList<>();
            BuiltInRegistries.BLOCK.forEach(all::add);
            return all;
        }
        Identifier key = Identifier.tryParse(lower);
        if (key != null) {
            Optional<Block> exact = BuiltInRegistries.BLOCK.getOptional(key);
            if (exact.isPresent()) {
                return List.of(exact.get());
            }
        }
        if (!isRegex(lower)) {
            return List.of();
        }
        java.util.regex.Pattern regex;
        try {
            regex = java.util.regex.Pattern.compile(lower);
        } catch (PatternSyntaxException e) {
            throw InputParseException.of("kawe.error.unknown-block", id);
        }
        List<Block> out = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier name = BuiltInRegistries.BLOCK.getKey(block);
            if (regex.matcher(name.getPath()).find()) {
                out.add(block);
            }
        }
        return out;
    }

    private static boolean properties(String inside) {
        if (inside.isBlank()) {
            return true;
        }
        for (int i = 0; i < inside.length(); i++) {
            if ("=<>~!".indexOf(inside.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int matchingOpen(String text) {
        int depth = 0;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ']') {
                depth++;
            } else if (c == '[' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isRegex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("*.+?()|\\^$[]{}".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean plain(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private record Condition(String property, String operator, String value, java.util.regex.Pattern regex) {
    }

    private static List<Condition> conditions(String props, List<Block> blocks, String full) throws InputParseException {
        List<Condition> out = new ArrayList<>();
        for (String part : props.split(",", -1)) {
            String p = part.trim();
            if (p.isEmpty()) {
                throw InputParseException.of("kawe.error.parser.bad-state-format", full);
            }
            String operator = null;
            int at = -1;
            for (String op : new String[] {"!=", "<=", ">=", "=", "<", ">", "~", "!"}) {
                int i = p.indexOf(op);
                if (i > 0 && (at == -1 || i < at || (i == at && op.length() > operator.length()))) {
                    at = i;
                    operator = op;
                }
            }
            if (operator == null) {
                throw InputParseException.of("kawe.error.parser.bad-state-format", full);
            }
            String name = p.substring(0, at).trim().toLowerCase(Locale.ROOT);
            String value = p.substring(at + operator.length()).trim().toLowerCase(Locale.ROOT);
            String kind = switch (operator) {
                case "~" -> "=";
                case "!" -> "!=";
                default -> operator;
            };
            if (value.isEmpty()) {
                throw InputParseException.of("kawe.error.parser.bad-state-format", full);
            }
            boolean known = false;
            boolean validValue = false;
            for (Block block : blocks) {
                Property<?> property = block.getStateDefinition().getProperty(name);
                if (property != null) {
                    known = true;
                    validValue |= property.getValue(value).isPresent();
                }
            }
            if (!known) {
                throw InputParseException.of("kawe.error.parser.unknown-property", name, blocks.size() == 1 ? BlockInput.id(blocks.getFirst()) : full);
            }
            java.util.regex.Pattern regex = null;
            if (kind.equals("=") && !plain(value)) {
                try {
                    regex = java.util.regex.Pattern.compile(value);
                } catch (PatternSyntaxException e) {
                    throw InputParseException.of("kawe.error.parser.unknown-value", value, name);
                }
            } else if (!validValue) {
                throw InputParseException.of("kawe.error.parser.unknown-value", value, name);
            }
            out.add(new Condition(name, kind, value, regex));
        }
        return out;
    }

    private static boolean matches(BlockState state, List<Condition> conditions) {
        for (Condition c : conditions) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(c.property);
            if (property == null) {
                return false;
            }
            String actual = valueName(state, property);
            boolean ok = switch (c.operator) {
                case "=" -> c.regex != null ? c.regex.matcher(actual).matches() : actual.equals(c.value);
                case "!=" -> !actual.equals(c.value);
                default -> compare(property, actual, c.value, c.operator);
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static <T extends Comparable<T>> int index(Property<T> property, String name) {
        int i = 0;
        for (T value : property.getPossibleValues()) {
            if (property.getName(value).equals(name)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static boolean compare(Property<?> property, String actual, String expected, String operator) {
        int a = index(property, actual);
        int b = index(property, expected);
        if (b < 0) {
            return false;
        }
        return switch (operator) {
            case "<" -> a < b;
            case ">" -> a > b;
            case "<=" -> a <= b;
            case ">=" -> a >= b;
            default -> false;
        };
    }

    public static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
