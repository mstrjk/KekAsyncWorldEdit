package teacommontea.kawe;

import org.bukkit.configuration.file.FileConfiguration;

public record Tuning(boolean idleTime, int idleMarginMs, boolean foliaChunkTasks, int foliaLingerMs, int foliaFirstHopMs, boolean foliaLingerWhileStaging, boolean preloadWhileTyping, int maxPreloadChunks, int preloadResourceThresholdMb,
                     boolean streamEdits, boolean sectionFill, boolean directSmallPackets, int bulkThreshold, int swapMin, boolean lighting, boolean foliaStreamEdits, boolean showTiming) {

    private static volatile Tuning current = new Tuning(true, 3, true, 3, 3, true, true, 64, 2000, true, true, true, 256, 64, true, false, true);

    public static Tuning get() {
        return current;
    }

    public static void load(FileConfiguration config) {
        current = new Tuning(
                config.getBoolean("queue.use-idle-time", true),
                Math.max(0, config.getInt("queue.idle-margin-ms", 3)),
                config.getBoolean("folia.chunk-tasks", true),
                Math.max(0, config.getInt("folia.linger-ms", 3)),
                Math.max(0, config.getInt("folia.first-hop-linger-ms", 3)),
                config.getBoolean("folia.linger-while-staging", true),
                config.getBoolean("folia.preload-while-type", true),
                Math.max(0, config.getInt("folia.max-preload-chunks", 64)),
                Math.max(0, config.getInt("folia.resource-threshold", 2000)),
                config.getBoolean("engine.stream-edits", true),
                config.getBoolean("engine.section-fill", true),
                config.getBoolean("engine.direct-small-packets", true),
                Math.max(1, config.getInt("engine.bulk-chunk-threshold", 256)),
                Math.max(1, Math.min(4096, config.getInt("engine.section-swap-min", 64))),
                config.getBoolean("engine.relight", true),
                config.getBoolean("folia.stream-edits", false),
                config.getBoolean("messages.show-timing", true));
    }
}
