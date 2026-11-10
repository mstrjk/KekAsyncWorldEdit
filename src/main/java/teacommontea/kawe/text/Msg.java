package teacommontea.kawe.text;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Msg {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static volatile Map<String, String> strings = Map.of();

    private Msg() {
    }

    public static void load(Plugin plugin) throws IOException {
        Map<String, String> loaded = new HashMap<>();
        try (InputStream in = plugin.getResource("lang/strings.json")) {
            if (in == null) {
                throw new IOException("lang/strings.json missing from jar");
            }
            read(new InputStreamReader(in, StandardCharsets.UTF_8), loaded);
        }
        Path override = plugin.getDataFolder().toPath().resolve("lang").resolve("strings.json");
        if (Files.isRegularFile(override)) {
            try (Reader reader = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                read(reader, loaded);
            }
        }
        strings = Map.copyOf(loaded);
    }

    private static void read(Reader reader, Map<String, String> into) {
        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
        for (String key : json.keySet()) {
            into.put(key, json.get(key).getAsString());
        }
    }

    public static boolean has(String key) {
        return strings.containsKey(key);
    }

    public static String raw(String key) {
        return strings.getOrDefault(key, key);
    }

    public static Component tr(String key, Object... args) {
        return format(raw(key), args);
    }

    public static Component format(String template, Object... args) {
        List<ComponentLike> out = new ArrayList<>();
        int i = 0;
        StringBuilder segment = new StringBuilder();
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = template.indexOf('}', i);
                if (close > i + 1 && isIndex(template, i + 1, close)) {
                    int index = Integer.parseInt(template, i + 1, close, 10);
                    flush(out, segment);
                    if (index < args.length) {
                        out.add(component(args[index]));
                    }
                    i = close + 1;
                    continue;
                }
            }
            segment.append(c);
            i++;
        }
        flush(out, segment);
        TextColor last = null;
        for (int j = 0; j < out.size(); j++) {
            Component child = out.get(j).asComponent();
            if (child.color() == null && last != null) {
                child = child.color(last);
                out.set(j, child);
            }
            TextColor tail = lastColor(child);
            if (tail != null) {
                last = tail;
            }
        }
        return Component.textOfChildren(out.toArray(ComponentLike[]::new));
    }

    private static TextColor lastColor(Component component) {
        List<Component> children = component.children();
        for (int i = children.size() - 1; i >= 0; i--) {
            TextColor color = lastColor(children.get(i));
            if (color != null) {
                return color;
            }
        }
        return component.color();
    }

    private static boolean isIndex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static void flush(List<ComponentLike> out, StringBuilder segment) {
        if (!segment.isEmpty()) {
            out.add(LEGACY.deserialize(segment.toString()));
            segment.setLength(0);
        }
    }

    public static Component component(Object arg) {
        if (arg instanceof Component c) {
            return c;
        }
        return Component.text(String.valueOf(arg));
    }

    public static Component prefixed(Component message) {
        return format(raw("prefix"), message);
    }

    public static void print(Audience to, Component message) {
        to.sendMessage(prefixed(message));
    }

    public static void print(Audience to, String key, Object... args) {
        print(to, tr(key, args));
    }

    public static void info(Audience to, String key, Object... args) {
        print(to, tr(key, args).colorIfAbsent(NamedTextColor.LIGHT_PURPLE));
    }

    public static void error(Audience to, String key, Object... args) {
        print(to, tr(key, args).colorIfAbsent(NamedTextColor.RED));
    }

    public static void debug(Audience to, String key, Object... args) {
        print(to, tr(key, args).colorIfAbsent(NamedTextColor.GRAY));
    }

    public static void infoText(Audience to, Component text) {
        print(to, text.colorIfAbsent(NamedTextColor.LIGHT_PURPLE));
    }

    public static void errorText(Audience to, Component text) {
        print(to, text.colorIfAbsent(NamedTextColor.RED));
    }
}
