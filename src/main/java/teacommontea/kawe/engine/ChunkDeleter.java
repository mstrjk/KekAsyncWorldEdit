package teacommontea.kawe.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChunkDeleter {

    public static final String FILE_NAME = "delete_chunks.json";
    private static final List<String> FOLDERS = List.of("region", "entities", "poi");
    private static final int HEADER = 8192;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();

    public static final class Batch {
        public String world;
        public List<int[]> chunks = new ArrayList<>();
        public int[] min;
        public int[] max;
        public Long before;

        boolean valid() {
            if (world == null || world.isBlank()) {
                return false;
            }
            if (min != null || max != null) {
                return min != null && max != null && min.length >= 2 && max.length >= 2;
            }
            if (chunks == null) {
                return false;
            }
            for (int[] chunk : chunks) {
                if (chunk == null || chunk.length < 2) {
                    return false;
                }
            }
            return true;
        }

        public long count() {
            if (min != null) {
                return ((long) Math.abs(max[0] - min[0]) + 1) * ((long) Math.abs(max[1] - min[1]) + 1);
            }
            return chunks.size();
        }
    }

    public static final class Info {
        public List<Batch> batches = new ArrayList<>();
    }

    private ChunkDeleter() {
    }

    public static Info read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new Info();
        }
        try {
            Info info = GSON.fromJson(Files.readString(file), Info.class);
            if (info == null || info.batches == null) {
                return new Info();
            }
            info.batches.removeIf(batch -> batch == null || !batch.valid());
            return info;
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid chunk deletion file: " + e.getMessage(), e);
        }
    }

    public static void write(Path file, Info info) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(info));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static Info append(Path file, Batch batch) throws IOException {
        synchronized (LOCK) {
            Info info = read(file);
            info.batches.add(batch);
            write(file, info);
            return info;
        }
    }

    private record Counts(long region, long entities, long poi) {
        Counts plus(Counts other) {
            return new Counts(region + other.region, entities + other.entities, poi + other.poi);
        }
    }

    public static void runPending(Path file, Logger logger) {
        if (!Files.exists(file)) {
            return;
        }
        Info info;
        try {
            info = read(file);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not read " + file + "; no chunks were deleted", e);
            return;
        }
        Counts total = new Counts(0, 0, 0);
        while (!info.batches.isEmpty()) {
            Batch batch = info.batches.getFirst();
            try {
                total = total.plus(runBatch(batch, logger));
            } catch (IOException | RuntimeException e) {
                logger.log(Level.SEVERE, "Chunk deletion stopped; the rest stays queued for the next start. Restore .bak files if the world misbehaves", e);
                return;
            }
            info.batches.removeFirst();
            try {
                if (info.batches.isEmpty()) {
                    Files.deleteIfExists(file);
                } else {
                    write(file, info);
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not update " + file + " after a finished batch; remove the finished batch by hand before the next start", e);
                return;
            }
        }
        logger.info("Deleted chunk entries: " + total.region() + " block, " + total.entities() + " entity, " + total.poi() + " poi");
    }

    private static List<int[]> chunks(Batch batch) {
        if (batch.min == null) {
            return batch.chunks;
        }
        List<int[]> out = new ArrayList<>();
        for (int x = Math.min(batch.min[0], batch.max[0]); x <= Math.max(batch.min[0], batch.max[0]); x++) {
            for (int z = Math.min(batch.min[1], batch.max[1]); z <= Math.max(batch.min[1], batch.max[1]); z++) {
                out.add(new int[]{x, z});
            }
        }
        return out;
    }

    private static Counts runBatch(Batch batch, Logger logger) throws IOException {
        Path world = Path.of(batch.world);
        Map<Long, List<int[]>> byRegion = new HashMap<>();
        for (int[] chunk : chunks(batch)) {
            byRegion.computeIfAbsent(ChunkKey.of(chunk[0] >> 5, chunk[1] >> 5), k -> new ArrayList<>()).add(chunk);
        }
        long[] counts = new long[FOLDERS.size()];
        for (Map.Entry<Long, List<int[]>> entry : byRegion.entrySet()) {
            String name = "r." + ChunkKey.x(entry.getKey()) + "." + ChunkKey.z(entry.getKey()) + ".mca";
            for (int f = 0; f < FOLDERS.size(); f++) {
                Path region = world.resolve(FOLDERS.get(f)).resolve(name);
                if (Files.exists(region)) {
                    counts[f] += clear(region, entry.getValue(), batch.before, logger);
                }
            }
        }
        return new Counts(counts[0], counts[1], counts[2]);
    }

    private static int index(int[] chunk) {
        return (chunk[0] & 31) + (chunk[1] & 31) * 32;
    }

    private static int clear(Path region, List<int[]> chunks, Long before, Logger logger) throws IOException {
        if (Files.size(region) < HEADER) {
            logger.warning("Skipping " + region + ": shorter than a region header");
            return 0;
        }
        Path backup = region.resolveSibling(region.getFileName() + ".bak");
        if (!Files.exists(backup)) {
            Files.copy(region, backup);
        }
        int cleared = 0;
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
            for (int[] chunk : chunks) {
                int index = index(chunk);
                file.seek(index * 4L);
                int location = file.readInt();
                file.seek(4096 + index * 4L);
                int timestamp = file.readInt();
                if (location == 0 || (before != null && timestamp >= before)) {
                    continue;
                }
                file.seek(index * 4L);
                file.writeInt(0);
                file.seek(4096 + index * 4L);
                file.writeInt(0);
                cleared++;
            }
        }
        return cleared;
    }
}
