package teacommontea.kawe.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

public final class PaginationBox {

    private static final int WIDTH = 47;

    private final String title;
    private final String pageCommand;
    private final List<Component> lines;
    private int perPage = 8;

    public PaginationBox(String title, String pageCommand, List<Component> lines) {
        this.title = title;
        this.pageCommand = normalize(pageCommand);
        this.lines = List.copyOf(lines);
    }

    public PaginationBox(String title, String pageCommand, int total, IntFunction<Component> line) {
        this.title = title;
        this.pageCommand = normalize(pageCommand);
        this.lines = new AbstractList<>() {
            @Override
            public Component get(int index) {
                return line.apply(index);
            }

            @Override
            public int size() {
                return total;
            }
        };
    }

    private static String normalize(String command) {
        if (command == null || command.contains("%page%")) {
            return command;
        }
        if (command.contains("-p ")) {
            return command.replaceAll("-p [0-9]+", "-p %page%");
        }
        return command + " -p %page%";
    }

    public PaginationBox perPage(int value) {
        this.perPage = value;
        return this;
    }

    public PaginationBox forConsole(boolean console) {
        if (console) {
            perPage = 20;
        }
        return this;
    }

    public Component create(int page) throws IllegalArgumentException {
        if (page == 1 && lines.isEmpty()) {
            return Component.text("No results found.");
        }
        int pageCount = (int) Math.ceil(lines.size() / (double) perPage);
        if (page < 1 || page > pageCount) {
            throw new IllegalArgumentException("page");
        }
        List<ComponentLike> out = new ArrayList<>();
        out.add(centered(Component.text(title)));
        out.add(Component.newline());
        int last = Math.min(page * perPage, lines.size());
        for (int i = (page - 1) * perPage; i < last; i++) {
            out.add(lines.get(i));
            if (i + 1 != last) {
                out.add(Component.newline());
            }
        }
        if (pageCount > 1) {
            out.add(Component.newline());
            TextComponent number = Component.text("Page ", NamedTextColor.YELLOW)
                    .append(Component.text(String.valueOf(page), NamedTextColor.GOLD))
                    .append(Component.text(" of "))
                    .append(Component.text(String.valueOf(pageCount), NamedTextColor.GOLD));
            if (pageCommand == null) {
                out.add(centered(number));
            } else {
                List<ComponentLike> nav = new ArrayList<>();
                if (page > 1) {
                    nav.add(Component.text("<<< ", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand(pageCommand.replace("%page%", String.valueOf(page - 1))))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to navigate"))));
                }
                nav.add(number);
                if (page < pageCount) {
                    nav.add(Component.text(" >>>", NamedTextColor.GOLD)
                            .clickEvent(ClickEvent.runCommand(pageCommand.replace("%page%", String.valueOf(page + 1))))
                            .hoverEvent(HoverEvent.showText(Component.text("Click to navigate"))));
                }
                out.add(centered(Component.textOfChildren(nav.toArray(ComponentLike[]::new))));
            }
        }
        return Component.textOfChildren(out.toArray(ComponentLike[]::new));
    }

    public static Component centered(Component text) {
        int length = PlainTextComponentSerializer.plainText().serialize(text).length();
        int side = (int) Math.floor((WIDTH - length) / 2.0);
        List<ComponentLike> line = new ArrayList<>();
        if (side > 0) {
            if (side > 1) {
                line.add(border(side - 1));
            }
            line.add(Component.space());
        }
        line.add(text);
        if (side > 0) {
            line.add(Component.space());
            if (side > 1) {
                line.add(border(side - 1));
            }
        }
        return Component.textOfChildren(line.toArray(ComponentLike[]::new));
    }

    private static Component border(int count) {
        return Component.text("-".repeat(count), NamedTextColor.YELLOW, TextDecoration.STRIKETHROUGH);
    }
}
