package teacommontea.kawe;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import teacommontea.kawe.command.BiomeCommands;
import teacommontea.kawe.command.BrushCommands;
import teacommontea.kawe.command.ChunkCommands;
import teacommontea.kawe.command.ClipboardCommands;
import teacommontea.kawe.command.Commands;
import teacommontea.kawe.command.GeneralCommands;
import teacommontea.kawe.command.GenerationCommands;
import teacommontea.kawe.command.HelpCommands;
import teacommontea.kawe.command.HistoryCommands;
import teacommontea.kawe.command.NavigationCommands;
import teacommontea.kawe.command.RegionCommands;
import teacommontea.kawe.command.SchematicCommands;
import teacommontea.kawe.command.SelectionCommands;
import teacommontea.kawe.command.ToolCommands;
import teacommontea.kawe.command.UtilityCommands;
import teacommontea.kawe.engine.ChunkDeleter;
import teacommontea.kawe.engine.RegionQueue;
import teacommontea.kawe.history.DiskHistory;
import teacommontea.kawe.listener.ToolListener;
import teacommontea.kawe.schematic.Schematics;
import teacommontea.kawe.session.Sessions;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.tool.ToolTag;

import java.io.IOException;

public final class Kawe extends JavaPlugin {

    @Override
    public void onLoad() {
        ChunkDeleter.runPending(getDataFolder().toPath().resolve(ChunkDeleter.FILE_NAME), getLogger());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Msg.load(this);
        } catch (IOException e) {
            getLogger().severe("Could not load messages: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        FileConfiguration config = getConfig();
        fillDefaults(this, config);
        try {
            Schematics.root(getDataFolder().toPath().resolve(config.getString("paths.schematics", "schematics")));
        } catch (IOException e) {
            getLogger().severe("Could not create schematic folder: " + e.getMessage());
        }
        try {
            DiskHistory.root(getDataFolder().toPath().resolve(config.getString("paths.history", "history")));
        } catch (IOException e) {
            getLogger().severe("Could not create history folder: " + e.getMessage());
        }
        Sched.start(this, threads(config.getInt("queue.parallel-threads", -1)),
                config.getInt("queue.command-threads", 4));
        RegionQueue.configure(config.getInt("queue.tick-budget-ms", 40), config.getInt("queue.max-in-flight", 8192));
        Sessions.maxHistory(config.getInt("history.size", 15));
        Settings.load(config);
        Tuning.load(config);
        ToolTag.init(this);
        Debug.timings(config.getBoolean("debug.timings", false));
        DiskHistory.warm();
        SelectionCommands.register();
        RegionCommands.register();
        HistoryCommands.register();
        GeneralCommands.register();
        ClipboardCommands.register();
        SchematicCommands.register();
        GenerationCommands.register();
        NavigationCommands.register();
        BiomeCommands.register();
        ChunkCommands.register();
        UtilityCommands.register();
        ToolCommands.register();
        BrushCommands.register();
        HelpCommands.register();
        Commands.register(this);
        getServer().getPluginManager().registerEvents(new ToolListener(), this);
        getLogger().info("KAWE enabled on " + (Sched.folia() ? "Folia" : "Paper") + " with " + Sched.threads() + " worker threads");
    }

    @Override
    public void onDisable() {
        Sched.stop();
        DiskHistory.close();
        Sessions.clear();
    }

    public static void fillDefaults(Plugin plugin, FileConfiguration config) {
        if (config.getDefaults() == null) {
            return;
        }
        boolean missing = false;
        for (String key : config.getDefaults().getKeys(true)) {
            if (!config.isSet(key)) {
                missing = true;
                break;
            }
        }
        if (missing) {
            config.options().copyDefaults(true);
            plugin.saveConfig();
        }
    }

    private static int threads(int configured) {
        return configured > 0 ? configured : Runtime.getRuntime().availableProcessors();
    }
}
