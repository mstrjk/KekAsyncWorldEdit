package teacommontea.kawe.session;

import org.bukkit.entity.Player;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Settings;
import teacommontea.kawe.history.DiskHistory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Sessions {

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static volatile int maxHistory = 15;

    private Sessions() {
    }

    public static void maxHistory(int value) {
        maxHistory = value;
    }

    public static Session of(Player player) {
        return of(player.getUniqueId());
    }

    public static Session of(UUID id) {
        Session existing = SESSIONS.get(id);
        if (existing != null) {
            return existing;
        }
        Session created = new Session(id, maxHistory);
        Session raced = SESSIONS.putIfAbsent(id, created);
        if (raced != null) {
            return raced;
        }
        int keep = Math.min(Settings.get().historyRestore(), maxHistory > 0 ? maxHistory : Integer.MAX_VALUE);
        if (keep > 0 && DiskHistory.enabled()) {
            created.queue(() -> Sched.async(() -> DiskHistory.restore(created, keep)));
        }
        return created;
    }

    public static Session existing(UUID id) {
        return SESSIONS.get(id);
    }

    public static void forget(UUID id) {
        SESSIONS.remove(id);
    }

    public static void clear() {
        SESSIONS.clear();
    }
}
