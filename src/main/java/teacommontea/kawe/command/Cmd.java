package teacommontea.kawe.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Cmd {

    @FunctionalInterface
    public interface Handler {
        void run(CmdContext context) throws Exception;
    }

    public record Param(String name, ArgType<?> type, String fallback, boolean optional) {
    }

    public record Flag(char key, String name, ArgType<?> type) {
    }

    final String name;
    final List<String> aliases = new ArrayList<>();
    final List<Param> params = new ArrayList<>();
    final Map<Character, Flag> flags = new LinkedHashMap<>();
    final Map<String, Cmd> subs = new LinkedHashMap<>();
    String permission;
    String description = "";
    boolean playerOnly;
    boolean queued = true;
    Handler handler;
    Confirm confirm;
    String confirmArgument;

    private Cmd(String name) {
        this.name = name;
    }

    public static Cmd of(String name) {
        return new Cmd(name);
    }

    public Cmd aliases(String... values) {
        aliases.addAll(List.of(values));
        return this;
    }

    public Cmd perm(String value) {
        this.permission = value;
        return this;
    }

    public Cmd desc(String value) {
        this.description = value;
        return this;
    }

    public Cmd player() {
        this.playerOnly = true;
        return this;
    }

    public Cmd immediate() {
        this.queued = false;
        return this;
    }

    public Cmd arg(String argName, ArgType<?> type) {
        params.add(new Param(argName, type, null, false));
        return this;
    }

    public Cmd opt(String argName, ArgType<?> type, String fallback) {
        params.add(new Param(argName, type, fallback, true));
        return this;
    }

    public Cmd flag(char key, String flagName) {
        flags.put(key, new Flag(key, flagName, null));
        return this;
    }

    public Cmd flag(char key, String flagName, ArgType<?> type) {
        flags.put(key, new Flag(key, flagName, type));
        return this;
    }

    public Cmd sub(Cmd child) {
        subs.put(child.name, child);
        return this;
    }

    Cmd findSub(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        Cmd direct = subs.get(lower);
        if (direct != null) {
            return direct;
        }
        for (Cmd child : subs.values()) {
            if (child.aliases.contains(lower)) {
                return child;
            }
        }
        return null;
    }

    public Cmd confirm(Confirm kind) {
        return confirm(kind, null);
    }

    public Cmd confirm(Confirm kind, String argument) {
        this.confirm = kind;
        this.confirmArgument = argument;
        return this;
    }

    public Cmd run(Handler value) {
        this.handler = value;
        return this;
    }

    public String name() {
        return name;
    }

    public List<String> aliases() {
        return aliases;
    }

    public String permission() {
        return permission;
    }

    public String description() {
        return description;
    }

    public String usage() {
        StringBuilder out = new StringBuilder("/").append(name);
        for (Flag flag : flags.values()) {
            out.append(" [-").append(flag.key);
            if (flag.type != null) {
                out.append(" <").append(flag.name).append('>');
            }
            out.append(']');
        }
        for (Param param : params) {
            out.append(' ').append(param.optional ? "[" : "<").append(param.name).append(param.optional ? "]" : ">");
        }
        return out.toString();
    }
}
