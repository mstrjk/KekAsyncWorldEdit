package teacommontea.kawe;

import org.bukkit.configuration.file.FileConfiguration;

public record Settings(int maxRadius, long confirmArea, int defaultChangeLimit, int maxChangeLimit, int butcherDefaultRadius, int maxButcherRadius, int timeout, int maxTimeout, boolean unstuckOnGenerate,
                       boolean navigationGlass, int navigationWandDistance, int loadAllFiles, long loadAllBytes, int maxBrushRadius, int maxSuperPickaxe, boolean historyDisk, int historyDeleteDays, int historyRestore, boolean regionRestrictions, boolean pdcToolTag) {

    private static volatile Settings current = new Settings(-1, 524288L, -1, -1, -1, -1, 100, 300, true, true, 50, 64, 67108864L, -1, 5, true, 7, 10, true, true);

    public static Settings get() {
        return current;
    }

    public static void load(FileConfiguration config) {
        current = new Settings(
                config.getInt("limits.max-radius", -1),
                config.getLong("limits.confirm-area", 524288L),
                config.getInt("limits.default-change-limit", -1),
                config.getInt("limits.max-change-limit", -1),
                config.getInt("limits.butcher-default-radius", -1),
                config.getInt("limits.max-butcher-radius", -1),
                config.getInt("expression.timeout-ms", 100),
                config.getInt("expression.max-timeout-ms", 300),
                config.getBoolean("generation.unstuck", true),
                config.getBoolean("navigation.use-glass", true),
                config.getInt("navigation.wand-max-distance", 50),
                config.getInt("schematics.loadall-max-files", 64),
                config.getLong("schematics.loadall-max-bytes", 67108864L),
                config.getInt("limits.max-brush-radius", -1),
                config.getInt("limits.max-superpickaxe-size", 5),
                config.getBoolean("history.use-disk", true),
                config.getInt("history.delete-after-days", 7),
                config.getInt("history.keep-after-restart", 10),
                config.getBoolean("region-restrictions", true),
                config.getBoolean("pdc-tool-tag", true));
    }
}
