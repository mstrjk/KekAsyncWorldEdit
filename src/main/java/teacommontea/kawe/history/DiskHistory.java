package teacommontea.kawe.history;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Settings;
import teacommontea.kawe.engine.ChangeSet;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.session.Session;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class DiskHistory {

    public static final UUID EVERYONE = new UUID(-1L, -1L);
    private static final UUID CONSOLE = new UUID(0L, 0L);
    private static final Map<String, WorldIndex> WORLDS = new ConcurrentHashMap<>();
    private static volatile Path root;
    private static ExecutorService writer;

    private DiskHistory() {
    }

    public static synchronized void root(Path value) throws IOException {
        Files.createDirectories(value);
        root = value;
        WORLDS.clear();
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "KAWE-History");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    public static synchronized void warm() {
        if (!enabled() || writer == null) {
            return;
        }
        List<String> worlds = Bukkit.getWorlds().stream().map(World::getName).toList();
        writer.execute(() -> worlds.forEach(DiskHistory::index));
    }

    public static synchronized void close() {
        if (writer == null) {
            return;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(60, TimeUnit.SECONDS)) {
                Sched.plugin().getLogger().warning("KAWE history writes did not finish within 60 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer = null;
    }

    private static synchronized CompletableFuture<Void> queue(Entry entry, IoTask task) {
        CompletableFuture<Void> previous = entry.pending;
        CompletableFuture<Void> next = previous.thenRunAsync(() -> {
            try {
                task.run();
            } catch (IOException e) {
                Sched.plugin().getLogger().log(Level.WARNING, "Could not save history " + entry.path(), e);
            }
        }, writer);
        entry.pending = next;
        return next;
    }

    private interface IoTask {
        void run() throws IOException;
    }

    public static boolean enabled() {
        return root != null && Settings.get().historyDisk();
    }

    public static String name(UUID owner) {
        if (CONSOLE.equals(owner)) {
            return "Console";
        }
        OfflinePlayer player = Bukkit.getOfflinePlayer(owner);
        return player.getName() == null ? owner.toString() : player.getName();
    }

    public static void save(UUID owner, ChangeSet changes) {
        if (!enabled() || changes.diskIndex() >= 0 || changes.isEmpty()) {
            return;
        }
        int[] bounds = changes.bounds();
        if (bounds == null) {
            return;
        }
        WorldIndex index = index(changes.world().bukkit().getName());
        synchronized (index) {
            int number = index.next(owner);
            Entry entry = new Entry(index.world, owner, number, changes.created(), changes.command(), bounds, changes.size(), false, false);
            index.entries.add(entry);
            changes.disk(owner, number);
            queue(entry, () -> {
                write(entry, changes);
                writeMeta(entry);
            });
        }
    }

    public enum State {
        ACTIVE, ROLLED_BACK
    }

    public static void mark(ChangeSet changes, boolean undone) {
        UUID owner = changes.diskOwner();
        if (owner == null || !enabled()) {
            return;
        }
        Entry entry = get(changes.world().bukkit().getName(), owner, changes.diskIndex());
        if (entry != null) {
            mark(entry, undone, false);
        }
    }

    public static void mark(Entry entry, boolean undone, boolean rolledBack) {
        entry.undone = undone;
        entry.rolledBack = rolledBack;
        queue(entry, () -> writeMeta(entry));
    }

    public static Entry get(String world, UUID owner, int number) {
        WorldIndex index = index(world);
        synchronized (index) {
            for (Entry entry : index.entries) {
                if (entry.owner.equals(owner) && entry.index == number) {
                    return entry;
                }
            }
        }
        return null;
    }

    public static List<Entry> edits(String world, UUID owner, long minTime, int[] box, State state) {
        WorldIndex index = index(world);
        List<Entry> out = new ArrayList<>();
        synchronized (index) {
            for (Entry entry : index.entries) {
                if ((owner == null || entry.owner.equals(owner)) && entry.time >= minTime && (state == null || (state == State.ACTIVE ? !entry.undone : entry.rolledBack))
                        && (box == null || entry.intersects(box))) {
                    out.add(entry);
                }
            }
        }
        out.sort(Comparator.comparingLong((Entry entry) -> entry.time).thenComparingInt(entry -> entry.index).reversed());
        return out;
    }

    public static void restore(Session session, int limit) {
        List<Entry> all = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entry entry : edits(world.getName(), session.owner(), Long.MIN_VALUE, null, null)) {
                if (!entry.rolledBack) {
                    all.add(entry);
                }
            }
        }
        all.sort(Comparator.comparingLong((Entry entry) -> entry.time).thenComparingInt(entry -> entry.index));
        List<Entry> redo = new ArrayList<>();
        List<Entry> done = new ArrayList<>();
        boolean tail = true;
        for (int i = all.size() - 1; i >= 0 && redo.size() + done.size() < limit; i--) {
            Entry entry = all.get(i);
            if (tail && entry.undone) {
                redo.addFirst(entry);
                continue;
            }
            tail = false;
            if (!entry.undone) {
                done.addFirst(entry);
            }
        }
        List<ChangeSet> steps = new ArrayList<>(done.size() + redo.size());
        int applied = 0;
        for (Entry entry : done) {
            ChangeSet changes = restoreOne(entry);
            if (changes != null) {
                steps.add(changes);
                applied++;
            }
        }
        for (Entry entry : redo) {
            ChangeSet changes = restoreOne(entry);
            if (changes != null) {
                steps.add(changes);
            }
        }
        session.restore(steps, applied);
    }

    private static ChangeSet restoreOne(Entry entry) {
        World world = Bukkit.getWorld(entry.world);
        if (world == null) {
            return null;
        }
        try {
            return load(entry, KWorld.of(world));
        } catch (IOException | RuntimeException e) {
            Sched.plugin().getLogger().log(Level.WARNING, "Could not restore history " + entry.path(), e);
            return null;
        }
    }

    public static ChangeSet load(Entry entry, KWorld world) throws IOException {
        Sched.join(entry.pending);
        CompoundTag tag = NbtIo.readCompressed(entry.path(), NbtAccounter.unlimitedHeap());
        ChangeSet changes = ChangeSet.load(world, tag);
        changes.disk(entry.owner, entry.index);
        return changes;
    }

    public static void store(Entry entry, ChangeSet changes) {
        queue(entry, () -> write(entry, changes));
    }

    private static void write(Entry entry, ChangeSet changes) throws IOException {
        Path file = entry.path();
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(changes.save(), temp);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeMeta(Entry entry) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("time", Long.toString(entry.time));
        properties.setProperty("command", entry.command);
        properties.setProperty("size", Long.toString(entry.size));
        properties.setProperty("undone", Boolean.toString(entry.undone));
        properties.setProperty("rolledback", Boolean.toString(entry.rolledBack));
        int[] b = entry.bounds;
        properties.setProperty("bounds", b[0] + "," + b[1] + "," + b[2] + "," + b[3] + "," + b[4] + "," + b[5]);
        Path file = entry.meta();
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            properties.store(writer, null);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static WorldIndex index(String world) {
        return WORLDS.computeIfAbsent(world, DiskHistory::scan);
    }

    private static WorldIndex scan(String world) {
        WorldIndex index = new WorldIndex(world);
        Path base = root;
        if (base == null) {
            return index;
        }
        Path folder = base.resolve(world);
        if (!Files.isDirectory(folder)) {
            return index;
        }
        long days = Settings.get().historyDeleteDays();
        long cutoff = days <= 0 ? Long.MIN_VALUE : System.currentTimeMillis() - days * 86400000L;
        try (Stream<Path> files = Files.find(folder, 2, (path, attributes) -> attributes.isRegularFile() && path.getFileName().toString().endsWith(".meta"))) {
            for (Path meta : files.toList()) {
                Entry entry = read(world, meta);
                if (entry == null) {
                    continue;
                }
                index.next.merge(entry.owner, entry.index + 1, Math::max);
                if (entry.time < cutoff) {
                    Files.deleteIfExists(entry.path());
                    Files.deleteIfExists(meta);
                } else {
                    index.entries.add(entry);
                }
            }
        } catch (IOException e) {
            Sched.plugin().getLogger().log(Level.WARNING, "Could not read history for " + world, e);
        }
        return index;
    }

    private static Entry read(String world, Path meta) {
        String file = meta.getFileName().toString();
        try {
            UUID owner = UUID.fromString(meta.getParent().getFileName().toString());
            int number = Integer.parseInt(file.substring(0, file.length() - ".meta".length()));
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(meta, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            String[] parts = properties.getProperty("bounds", "").split(",");
            if (parts.length != 6) {
                return null;
            }
            int[] bounds = new int[6];
            for (int i = 0; i < 6; i++) {
                bounds[i] = Integer.parseInt(parts[i]);
            }
            return new Entry(world, owner, number, Long.parseLong(properties.getProperty("time", "0")), properties.getProperty("command", ""),
                    bounds, Long.parseLong(properties.getProperty("size", "0")), Boolean.parseBoolean(properties.getProperty("undone", "false")),
                    Boolean.parseBoolean(properties.getProperty("rolledback", "false")));
        } catch (IllegalArgumentException | IOException e) {
            Sched.plugin().getLogger().warning("Skipping unreadable history " + meta + ": " + e.getMessage());
            return null;
        }
    }

    private static final class WorldIndex {
        final String world;
        final List<Entry> entries = new ArrayList<>();
        final Map<UUID, Integer> next = new HashMap<>();

        WorldIndex(String world) {
            this.world = world;
        }

        int next(UUID owner) {
            int number = next.getOrDefault(owner, 0);
            next.put(owner, number + 1);
            return number;
        }
    }

    public static final class Entry {
        private final String world;
        private final UUID owner;
        private final int index;
        private final long time;
        private final String command;
        private final int[] bounds;
        private final long size;
        private volatile boolean undone;
        private volatile boolean rolledBack;
        private volatile CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

        Entry(String world, UUID owner, int index, long time, String command, int[] bounds, long size, boolean undone, boolean rolledBack) {
            this.world = world;
            this.owner = owner;
            this.index = index;
            this.time = time;
            this.command = command;
            this.bounds = bounds;
            this.size = size;
            this.undone = undone;
            this.rolledBack = rolledBack;
        }

        public String world() {
            return world;
        }

        public UUID owner() {
            return owner;
        }

        public int index() {
            return index;
        }

        public long time() {
            return time;
        }

        public String command() {
            return command;
        }

        public int[] bounds() {
            return bounds.clone();
        }

        public long size() {
            return size;
        }

        public boolean undone() {
            return undone;
        }

        public boolean contains(int x, int y, int z) {
            return x >= bounds[0] && y >= bounds[1] && z >= bounds[2] && x <= bounds[3] && y <= bounds[4] && z <= bounds[5];
        }

        boolean intersects(int[] box) {
            return box[0] <= bounds[3] && box[3] >= bounds[0] && box[1] <= bounds[4] && box[4] >= bounds[1] && box[2] <= bounds[5] && box[5] >= bounds[2];
        }

        public Path path() {
            return root.resolve(world).resolve(owner.toString()).resolve(index + ".bd");
        }

        Path meta() {
            return root.resolve(world).resolve(owner.toString()).resolve(index + ".meta");
        }

        public long diskSize() {
            try {
                return Files.size(path());
            } catch (IOException e) {
                return 0;
            }
        }
    }
}
