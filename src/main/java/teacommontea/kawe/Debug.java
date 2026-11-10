package teacommontea.kawe;

public final class Debug {

    private static volatile boolean timings;

    private Debug() {
    }

    public static void timings(boolean value) {
        timings = value;
    }

    public static boolean timings() {
        return timings;
    }

    public static void time(String message) {
        Sched.plugin().getLogger().info("[kawe-time] t=" + System.nanoTime() / 1_000_000L + " " + message);
    }
}
