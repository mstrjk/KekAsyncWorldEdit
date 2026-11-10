package teacommontea.kawe.schematic;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import teacommontea.kawe.clipboard.Clipboard;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class Schematics {

    private static Path root;

    private Schematics() {
    }

    public static void root(Path value) throws IOException {
        root = value.toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public static Path root() {
        return root;
    }

    public static boolean inside(Path file) {
        return file.toAbsolutePath().normalize().startsWith(root);
    }

    public static Path path(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Path relative;
        try {
            relative = Path.of(name.replace('\\', '/'));
        } catch (InvalidPathException e) {
            return null;
        }
        if (relative.isAbsolute() || relative.getRoot() != null) {
            return null;
        }
        Path resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    public static Path resolve(String name, Format format) {
        Path base = path(name);
        if (base == null) {
            return null;
        }
        if (Files.isRegularFile(base)) {
            return base;
        }
        if (format != null) {
            Path withExt = path(name + "." + format.extension());
            return withExt != null && Files.isRegularFile(withExt) ? withExt : null;
        }
        for (String ext : new String[] {"schem", "schematic", "nbt"}) {
            Path withExt = path(name + "." + ext);
            if (withExt != null && Files.isRegularFile(withExt)) {
                return withExt;
            }
        }
        return null;
    }

    public static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    public static CompoundTag readTag(Path file) throws IOException {
        return NbtReader.read(file);
    }

    public static Clipboard load(Path file, Format format, Registry<Biome> biomes) throws IOException {
        if (format != null && !format.readable()) {
            throw new IOException("Format " + format.name() + " cannot be read");
        }
        CompoundTag tag = readTag(file);
        Format actual = format;
        if (actual == null || !actual.matches(tag)) {
            Format detected = Format.detect(tag);
            if (detected == null || (format != null && !sameFamily(format, detected))) {
                throw new IOException(file.getFileName().toString());
            }
            actual = detected;
        }
        return actual.read(tag, biomes);
    }

    private static boolean sameFamily(Format requested, Format detected) {
        return requested.extension().equals(detected.extension());
    }

    public static void save(Clipboard clipboard, Path file, Format format, boolean replace) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                format.write(clipboard, out);
            }
            move(temp, file, replace);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static List<Clipboard> loadAll(String name, int limit) throws IOException {
        Path target = path(name);
        List<Path> files = new ArrayList<>();
        if (target != null && Files.isDirectory(target)) {
            try (Stream<Path> list = Files.list(target)) {
                list.filter(Files::isRegularFile).filter(p -> formatOf(p) != null).limit(limit).forEach(files::add);
            }
        } else {
            Path single = resolve(name, null);
            if (single != null) {
                files.add(single);
            }
        }
        Registry<Biome> biomes = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
        List<Clipboard> out = new ArrayList<>();
        for (Path file : files) {
            out.add(load(file, null, biomes));
        }
        return out;
    }

    public static Format formatOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : Format.byExtension(name.substring(dot + 1));
    }
}
