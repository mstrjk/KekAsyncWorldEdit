package teacommontea.kawe.schematic;

import java.io.IOException;

final class VarIntReader {

    private final byte[] data;
    private int position;

    VarIntReader(byte[] data) {
        this.data = data;
    }

    int next() throws IOException {
        int value = 0;
        int shift = 0;
        while (true) {
            if (position >= data.length) {
                throw new IOException("Truncated varint at byte " + position);
            }
            byte b = data[position++];
            value |= (b & 127) << shift;
            if ((b & 128) == 0) {
                return value;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("Varint too long at byte " + position);
            }
        }
    }

    boolean exhausted() {
        return position >= data.length;
    }
}
