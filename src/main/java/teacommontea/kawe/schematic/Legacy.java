package teacommontea.kawe.schematic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Legacy {

    private static final int LEGACY_STATES = 1631;

    private record Tables(Map<Integer, BlockState> byId, Map<BlockState, Integer> byState, Map<Block, Integer> byBlock) {
    }

    private static volatile Tables tables;

    private Legacy() {
    }

    private static Tables tables() throws IOException {
        Tables loaded = tables;
        if (loaded != null) {
            return loaded;
        }
        synchronized (Legacy.class) {
            if (tables != null) {
                return tables;
            }
            try (InputStream in = Legacy.class.getResourceAsStream("/data/legacy.json")) {
                if (in == null) {
                    throw new IOException("data/legacy.json missing from jar");
                }
                JsonObject blocks = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("blocks");
                List<String> keys = new ArrayList<>(blocks.keySet());
                List<String> names = new ArrayList<>(keys.size());
                for (String key : keys) {
                    names.add(blocks.get(key).getAsString());
                }
                List<Fixer.Resolved> fixed = Fixer.states(names, LEGACY_STATES);
                Map<Integer, BlockState> byId = new HashMap<>();
                Map<BlockState, Integer> byState = new HashMap<>();
                Map<Block, Integer> byBlock = new HashMap<>();
                for (int k = 0; k < keys.size(); k++) {
                    BlockState state = fixed.get(k).state();
                    if (state == null) {
                        continue;
                    }
                    String[] parts = keys.get(k).split(":");
                    int combined = (Integer.parseInt(parts[0]) << 4) | Integer.parseInt(parts[1]);
                    byId.put(combined, state);
                    byState.merge(state, combined, Math::min);
                    byBlock.merge(state.getBlock(), combined, Math::min);
                }
                Tables built = new Tables(Map.copyOf(byId), Map.copyOf(byState), Map.copyOf(byBlock));
                tables = built;
                return built;
            }
        }
    }

    private static Tables loaded() {
        try {
            return tables();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BlockState state(int id, int data) {
        if (id < 0 || data < 0 || data > 15) {
            return null;
        }
        return loaded().byId().get((id << 4) | data);
    }

    public static int combined(Block block) {
        return loaded().byBlock().getOrDefault(block, 0);
    }

    public static int combined(BlockState state) {
        Tables loaded = loaded();
        Integer exact = loaded.byState().get(state);
        return exact != null ? exact : loaded.byBlock().getOrDefault(state.getBlock(), 0);
    }
}
