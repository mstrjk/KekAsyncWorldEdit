package teacommontea.kawe.engine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import teacommontea.kawe.Tuning;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.selector.Selector;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.session.Sessions;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Preload {

    private static final long COOLDOWN_NANOS = 2_000_000_000L;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();
    private static final Map<UUID, String> SELECTED = new ConcurrentHashMap<>();

    private Preload() {
    }

    public static void typing(Player player) {
        if (!allowed()) {
            return;
        }
        long now = System.nanoTime();
        Long last = LAST.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_NANOS) {
            return;
        }
        LAST.put(player.getUniqueId(), now);
        load(player);
    }

    public static void selected(UUID owner) {
        if (!allowed()) {
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player == null) {
            return;
        }
        Region region = selection(player, player.getWorld());
        if (region == null) {
            return;
        }
        String bounds = player.getWorld().getName() + region.min() + region.max();
        if (bounds.equals(SELECTED.put(owner, bounds))) {
            return;
        }
        LAST.put(owner, System.nanoTime());
        load(player);
    }

    private static boolean allowed() {
        Tuning tuning = Tuning.get();
        if (!tuning.preloadWhileTyping() || tuning.maxPreloadChunks() <= 0) {
            return false;
        }
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return free >= tuning.preloadResourceThresholdMb() * 1024L * 1024L;
    }

    private static void load(Player player) {
        int max = Tuning.get().maxPreloadChunks();
        World world = player.getWorld();
        Location at = player.getLocation();
        int centerX = at.getBlockX() >> 4;
        int centerZ = at.getBlockZ() >> 4;
        int minX = Integer.MIN_VALUE;
        int maxX = Integer.MAX_VALUE;
        int minZ = Integer.MIN_VALUE;
        int maxZ = Integer.MAX_VALUE;
        Region region = selection(player, world);
        if (region != null) {
            BlockVec low = region.min();
            BlockVec high = region.max();
            minX = low.x() >> 4;
            maxX = high.x() >> 4;
            minZ = low.z() >> 4;
            maxZ = high.z() >> 4;
            centerX = Math.clamp(centerX, minX, maxX);
            centerZ = Math.clamp(centerZ, minZ, maxZ);
        }
        int loaded = 0;
        for (int ring = 0; loaded < max; ring++) {
            boolean inside = false;
            for (int dx = -ring; dx <= ring && loaded < max; dx++) {
                for (int dz = -ring; dz <= ring && loaded < max; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) {
                        continue;
                    }
                    inside = true;
                    world.getChunkAtAsync(x, z, true);
                    loaded++;
                }
            }
            if (!inside) {
                return;
            }
        }
    }

    private static Region selection(Player player, World world) {
        Session session = Sessions.existing(player.getUniqueId());
        if (session == null || !world.equals(session.selectorWorld())) {
            return null;
        }
        Selector selector = session.selectorRaw();
        return selector == null ? null : selector.partialRegion().orElse(null);
    }
}
