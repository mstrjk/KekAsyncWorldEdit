package teacommontea.kawe.schematic;

import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.biome.Biome;
import teacommontea.kawe.clipboard.Clipboard;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

public enum Format {

    FAST_V3("schem", true, "fast", "schem"),
    FAST_V2("schem", true, "fast.2", "schem.2"),
    MCEDIT_SCHEMATIC("schematic", false, "mcedit", "mce", "schematic"),
    SPONGE_V1_SCHEMATIC("schem", false, "sponge.1"),
    SPONGE_V2_SCHEMATIC("schem", true, "slow.2", "safe.2", "sponge.2"),
    SPONGE_V3_SCHEMATIC("schem", true, "sponge.3", "slow", "safe"),
    BROKENENTITY("schem", true, "brokenentity", "legacyentity", "le", "be", "brokenentities", "legacyentities"),
    MINECRAFT_STRUCTURE("nbt", true, "structure"),
    PNG("png", true, "png", "image");

    private final String extension;
    private final boolean writable;
    private final List<String> aliases;

    Format(String extension, boolean writable, String... aliases) {
        this.extension = extension;
        this.writable = writable;
        this.aliases = List.of(aliases);
    }

    public String extension() {
        return extension;
    }

    public boolean writable() {
        return writable;
    }

    public boolean readable() {
        return this != PNG;
    }

    public List<String> aliases() {
        return aliases;
    }

    public static Format byAlias(String alias) {
        String lower = alias.toLowerCase(Locale.ROOT);
        for (Format format : values()) {
            if (format.aliases.contains(lower)) {
                return format;
            }
        }
        return null;
    }

    public static Format byExtension(String extension) {
        String lower = extension.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "schem" -> FAST_V3;
            case "schematic" -> MCEDIT_SCHEMATIC;
            case "nbt" -> MINECRAFT_STRUCTURE;
            default -> null;
        };
    }

    public static Format detect(CompoundTag root) {
        if (Sponge.isSponge(root)) {
            int version = Sponge.version(root);
            return version >= 3 ? FAST_V3 : version == 2 ? FAST_V2 : SPONGE_V1_SCHEMATIC;
        }
        if (McEdit.isMcEdit(root)) {
            return MCEDIT_SCHEMATIC;
        }
        if (Structure.isStructure(root)) {
            return MINECRAFT_STRUCTURE;
        }
        return null;
    }

    public boolean matches(CompoundTag root) {
        return switch (this) {
            case FAST_V3, SPONGE_V3_SCHEMATIC -> Sponge.isSponge(root) && Sponge.version(root) >= 3;
            case FAST_V2, SPONGE_V2_SCHEMATIC, BROKENENTITY -> Sponge.isSponge(root) && Sponge.version(root) == 2;
            case SPONGE_V1_SCHEMATIC -> Sponge.isSponge(root) && Sponge.version(root) == 1;
            case MCEDIT_SCHEMATIC -> McEdit.isMcEdit(root);
            case MINECRAFT_STRUCTURE -> Structure.isStructure(root);
            case PNG -> false;
        };
    }

    public Clipboard read(CompoundTag root, Registry<Biome> biomes) throws IOException {
        return switch (this) {
            case FAST_V3, FAST_V2, SPONGE_V1_SCHEMATIC, SPONGE_V2_SCHEMATIC, SPONGE_V3_SCHEMATIC -> Sponge.read(root, biomes, false);
            case BROKENENTITY -> Sponge.read(root, biomes, true);
            case MCEDIT_SCHEMATIC -> McEdit.read(root);
            case MINECRAFT_STRUCTURE -> Structure.read(root);
            case PNG -> throw new IOException("Format " + name() + " cannot be read");
        };
    }

    public void write(Clipboard clipboard, OutputStream out) throws IOException {
        switch (this) {
            case FAST_V3, SPONGE_V3_SCHEMATIC -> NbtIo.writeCompressed(Sponge.write(clipboard, 3, false), out);
            case FAST_V2, SPONGE_V2_SCHEMATIC -> NbtIo.writeCompressed(Sponge.write(clipboard, 2, false), out);
            case BROKENENTITY -> NbtIo.writeCompressed(Sponge.write(clipboard, 2, true), out);
            case MINECRAFT_STRUCTURE -> NbtIo.writeCompressed(Structure.write(clipboard), out);
            case PNG -> Isometric.write(clipboard, out);
            case MCEDIT_SCHEMATIC, SPONGE_V1_SCHEMATIC -> throw new IOException("Format " + name() + " cannot be written");
        }
    }
}
