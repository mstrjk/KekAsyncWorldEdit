package teacommontea.kawe.command;

import teacommontea.kawe.parse.InputParseException;

import java.util.List;

public interface ArgType<T> {

    T parse(String raw, CmdContext context) throws InputParseException;

    default List<String> suggest(String partial, CmdContext context) {
        return List.of();
    }

    default boolean accepts(String raw) {
        return true;
    }

    default boolean greedy() {
        return false;
    }
}
