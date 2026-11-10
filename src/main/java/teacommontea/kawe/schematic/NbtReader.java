package teacommontea.kawe.schematic;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

final class NbtReader {

    private static final int MAX_DEPTH = 512;

    private NbtReader() {
    }

    static CompoundTag read(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 65536)) {
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            InputStream body = first == 0x1F && second == 0x8B ? new BufferedInputStream(new GZIPInputStream(raw, 65536), 65536) : raw;
            DataInputStream in = new DataInputStream(body);
            int type = in.readUnsignedByte();
            if (type != Tag.TAG_COMPOUND) {
                throw new IOException("Root tag is not a compound");
            }
            in.readUTF();
            return (CompoundTag) tag(in, type, 0);
        }
    }

    private static Tag tag(DataInput in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested too deeply");
        }
        return switch (type) {
            case Tag.TAG_BYTE -> ByteTag.valueOf(in.readByte());
            case Tag.TAG_SHORT -> ShortTag.valueOf(in.readShort());
            case Tag.TAG_INT -> IntTag.valueOf(in.readInt());
            case Tag.TAG_LONG -> LongTag.valueOf(in.readLong());
            case Tag.TAG_FLOAT -> FloatTag.valueOf(in.readFloat());
            case Tag.TAG_DOUBLE -> DoubleTag.valueOf(in.readDouble());
            case Tag.TAG_BYTE_ARRAY -> {
                byte[] value = new byte[length(in)];
                in.readFully(value);
                yield new ByteArrayTag(value);
            }
            case Tag.TAG_STRING -> StringTag.valueOf(in.readUTF());
            case Tag.TAG_LIST -> {
                int element = in.readUnsignedByte();
                int size = length(in);
                ListTag list = new ListTag();
                for (int i = 0; i < size; i++) {
                    list.add(tag(in, element, depth + 1));
                }
                yield list;
            }
            case Tag.TAG_COMPOUND -> {
                CompoundTag compound = new CompoundTag();
                int child;
                while ((child = in.readUnsignedByte()) != Tag.TAG_END) {
                    String name = in.readUTF();
                    compound.put(name, tag(in, child, depth + 1));
                }
                yield compound;
            }
            case Tag.TAG_INT_ARRAY -> {
                int[] value = new int[length(in)];
                for (int i = 0; i < value.length; i++) {
                    value[i] = in.readInt();
                }
                yield new IntArrayTag(value);
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] value = new long[length(in)];
                for (int i = 0; i < value.length; i++) {
                    value[i] = in.readLong();
                }
                yield new LongArrayTag(value);
            }
            default -> throw new IOException("Unknown NBT tag type " + type);
        };
    }

    private static int length(DataInput in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative NBT length " + length);
        }
        return length;
    }
}
