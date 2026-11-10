package teacommontea.kawe.engine;

import java.util.Arrays;

final class IntSlots {

    private static final int EMPTY = Integer.MIN_VALUE;

    private int[] keys;
    private int[] values;
    private int size;

    IntSlots() {
        keys = new int[128];
        values = new int[128];
        Arrays.fill(keys, EMPTY);
    }

    int get(int key) {
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (true) {
            int k = keys[i];
            if (k == EMPTY) {
                return -1;
            }
            if (k == key) {
                return values[i];
            }
            i = (i + 1) & mask;
        }
    }

    void put(int key, int value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int mask = keys.length - 1;
        int i = mix(key) & mask;
        while (keys[i] != EMPTY && keys[i] != key) {
            i = (i + 1) & mask;
        }
        if (keys[i] == EMPTY) {
            size++;
        }
        keys[i] = key;
        values[i] = value;
    }

    private void grow() {
        int[] oldKeys = keys;
        int[] oldValues = values;
        keys = new int[oldKeys.length << 1];
        values = new int[oldValues.length << 1];
        Arrays.fill(keys, EMPTY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int mix(int key) {
        int h = key * 0x9E3779B9;
        return h ^ (h >>> 16);
    }
}
