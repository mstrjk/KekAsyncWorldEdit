package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import teacommontea.kawe.Debug;
import teacommontea.kawe.Kawe;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Settings;
import teacommontea.kawe.Tuning;
import teacommontea.kawe.history.DiskHistory;
import teacommontea.kawe.schematic.Schematics;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.session.Sessions;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class HelpCommands {

    private HelpCommands() {
    }

    private static Cmd help(String name) {
        return Cmd.of(name).perm("kawe.help").desc("Displays help for KAWE commands").immediate()
                .flag('s', "List sub-commands of the given command, if applicable").flag('p', "The page to retrieve", Types.INT)
                .opt("command", Types.TEXT, "").run(HelpCommands::help);
    }

    public static void register() {
        Commands.add(help("/help"));
        Commands.add(Cmd.of("kawe").desc("KAWE commands")
                .sub(Cmd.of("version").aliases("ver").perm("kawe.version").desc("Get the KAWE version").immediate().run(HelpCommands::version))
                .sub(Cmd.of("reload").perm("kawe.reload").desc("Reload configuration and translations").run(HelpCommands::reload))
                .sub(help("help")));
    }

    private static boolean visible(Actor actor, Cmd command) {
        if (command.permission != null) {
            return actor.has(command.permission);
        }
        if (command.subs.isEmpty()) {
            return true;
        }
        for (Cmd child : command.subs.values()) {
            if (visible(actor, child)) {
                return true;
            }
        }
        return false;
    }

    private static Component line(String label, Cmd command) {
        return Component.text("/" + label, NamedTextColor.GOLD).clickEvent(ClickEvent.suggestCommand("//help " + label))
                .append(Component.text(command.description.isEmpty() ? "" : " - " + command.description, NamedTextColor.GRAY));
    }

    private static void help(CmdContext c) throws Exception {
        Integer page = c.flagValue('p', Types.INT);
        String raw = c.raw("command");
        Actor actor = c.actor();
        if (raw == null || raw.isBlank()) {
            List<Cmd> commands = new ArrayList<>();
            for (Cmd command : Commands.all()) {
                if (visible(actor, command)) {
                    commands.add(command);
                }
            }
            commands.sort(Comparator.comparing(command -> command.name.replace("/", "")));
            List<Component> lines = new ArrayList<>();
            for (Cmd command : commands) {
                lines.add(line(command.name, command));
            }
            show(c, "Help", "//help -p %page%", lines, page);
            return;
        }
        String[] parts = raw.trim().split("\\s+");
        String head = parts[0].startsWith("/") ? parts[0].substring(1) : parts[0];
        Cmd command = Commands.find(head.toLowerCase(Locale.ROOT));
        if (command == null) {
            command = Commands.find(parts[0].toLowerCase(Locale.ROOT));
        }
        if (command == null) {
            command = Commands.find("/" + head.toLowerCase(Locale.ROOT));
        }
        if (command == null || !visible(actor, command)) {
            c.error("kawe.help.command-not-found", raw);
            return;
        }
        String label = command.name;
        for (int i = 1; i < parts.length; i++) {
            if (command.subs.isEmpty()) {
                c.error("kawe.help.no-subcommands", label, parts[i]);
                return;
            }
            Cmd child = command.findSub(parts[i]);
            if (child == null || !visible(actor, child)) {
                c.error("kawe.help.subcommand-not-found", parts[i], label);
                return;
            }
            command = child;
            label = label + " " + child.name;
        }
        if (!command.subs.isEmpty() || c.flag('s')) {
            List<Component> lines = new ArrayList<>();
            for (Cmd child : command.subs.values()) {
                if (visible(actor, child)) {
                    lines.add(line(label + " " + child.name, child));
                }
            }
            if (lines.isEmpty()) {
                c.error("kawe.help.no-subcommands", label, "");
                return;
            }
            show(c, "Help for /" + label, "//help -s -p %page% " + label, lines, page);
            return;
        }
        c.print(Component.text("/" + label, NamedTextColor.GOLD).append(Component.text(" - " + command.description, NamedTextColor.GRAY)));
        String usage = command.usage();
        c.print(Component.text("Usage: /" + label + usage.substring(1 + command.name.length()), NamedTextColor.YELLOW));
        if (!command.aliases.isEmpty()) {
            c.print(Component.text("Aliases: " + String.join(", ", command.aliases), NamedTextColor.GRAY));
        }
        for (Cmd.Flag flag : command.flags.values()) {
            c.print(Component.text("  -" + flag.key() + (flag.type() == null ? "" : " <value>") + "  " + flag.name(), NamedTextColor.GRAY));
        }
        if (command.permission != null) {
            c.print(Component.text("Permission: " + command.permission, NamedTextColor.DARK_GRAY));
        }
    }

    private static void show(CmdContext c, String title, String pageCommand, List<Component> lines, Integer page) {
        try {
            c.print(new PaginationBox(title, pageCommand, lines).forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    private static void version(CmdContext c) {
        Plugin plugin = Sched.plugin();
        c.print("kawe.version.version", "-", "-", plugin.getPluginMeta().getVersion(),
                (Sched.folia() ? "Folia " : "Paper ") + Bukkit.getMinecraftVersion());
    }

    private static void reload(CmdContext c) throws Exception {
        Plugin plugin = Sched.plugin();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        Kawe.fillDefaults(plugin, config);
        Msg.load(plugin);
        Settings.load(config);
        Sessions.maxHistory(config.getInt("history.size", 15));
        Debug.timings(config.getBoolean("debug.timings", false));
        Tuning.load(config);
        Schematics.root(plugin.getDataFolder().toPath().resolve(config.getString("paths.schematics", "schematics")));
        DiskHistory.root(plugin.getDataFolder().toPath().resolve(config.getString("paths.history", "history")));
        c.print("kawe.reload.config");
    }

}
