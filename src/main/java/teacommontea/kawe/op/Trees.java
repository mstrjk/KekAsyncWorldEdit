package teacommontea.kawe.op;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.craftbukkit.block.CraftBlockState;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class Trees {

    private static final Set<Block> SOIL = Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MOSS_BLOCK,
            Blocks.ROOTED_DIRT, Blocks.MUD);
    private static final Set<Block> FUNGUS_SOIL = Set.of(Blocks.MYCELIUM, Blocks.WARPED_NYLIUM, Blocks.CRIMSON_NYLIUM);

    public enum Kind {
        TREE(TreeType.TREE, "oak", "tree", "regular"),
        BIG_TREE(TreeType.BIG_TREE, "largeoak", "bigoak", "big", "bigtree"),
        REDWOOD(TreeType.REDWOOD, "spruce", "redwood", "sequoia", "sequoioideae"),
        TALL_REDWOOD(TreeType.TALL_REDWOOD, "tallspruce", "bigspruce", "tallredwood", "tallsequoia", "tallsequoioideae"),
        MEGA_REDWOOD(TreeType.MEGA_REDWOOD, "largespruce", "megaredwood"),
        RANDOM_REDWOOD(null, "randspruce", "randredwood", "randomredwood", "anyredwood"),
        BIRCH(TreeType.BIRCH, "birch", "white", "whitebark"),
        TALL_BIRCH(TreeType.TALL_BIRCH, "tallbirch"),
        RANDOM_BIRCH(null, "randbirch", "randombirch"),
        JUNGLE(TreeType.JUNGLE, "jungle"),
        SMALL_JUNGLE(TreeType.SMALL_JUNGLE, "smalljungle"),
        SHORT_JUNGLE(TreeType.SMALL_JUNGLE, "shortjungle"),
        RANDOM_JUNGLE(null, "randjungle", "randomjungle"),
        JUNGLE_BUSH(TreeType.JUNGLE_BUSH, "junglebush", "jungleshrub"),
        RED_MUSHROOM(TreeType.RED_MUSHROOM, "redmushroom", "redgiantmushroom"),
        BROWN_MUSHROOM(TreeType.BROWN_MUSHROOM, "brownmushroom", "browngiantmushroom"),
        CRIMSON_FUNGUS(TreeType.CRIMSON_FUNGUS, "crimsonfungus", "rednethermushroom"),
        WARPED_FUNGUS(TreeType.WARPED_FUNGUS, "warpedfungus", "greennethermushroom"),
        RANDOM_MUSHROOM(null, "randmushroom", "randommushroom"),
        SWAMP(TreeType.SWAMP, "swamp", "swamptree"),
        ACACIA(TreeType.ACACIA, "acacia"),
        AZALEA(TreeType.AZALEA, "azalea"),
        DARK_OAK(TreeType.DARK_OAK, "darkoak"),
        PINE(null, "pine"),
        CHORUS_PLANT(TreeType.CHORUS_PLANT, "chorusplant"),
        MANGROVE(TreeType.MANGROVE, "mangrove"),
        TALL_MANGROVE(TreeType.TALL_MANGROVE, "tall_mangrove"),
        CHERRY(TreeType.CHERRY, "cherry"),
        PALE_OAK(TreeType.PALE_OAK, "pale_oak"),
        PALE_OAK_CREAKING(TreeType.PALE_OAK_CREAKING, "pale_oak_creaking"),
        RANDOM(null, "rand", "random");

        private final TreeType bukkit;
        private final List<String> aliases;

        Kind(TreeType bukkit, String... aliases) {
            this.bukkit = bukkit;
            this.aliases = List.of(aliases);
        }

        public List<String> aliases() {
            return aliases;
        }

        public static Kind lookup(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (Kind kind : values()) {
                if (kind.aliases.contains(lower) || kind.name().equalsIgnoreCase(lower)) {
                    return kind;
                }
            }
            return null;
        }

        Kind resolve(Random random) {
            return switch (this) {
                case RANDOM_REDWOOD -> pick(random, REDWOOD, TALL_REDWOOD, MEGA_REDWOOD);
                case RANDOM_BIRCH -> pick(random, BIRCH, TALL_BIRCH);
                case RANDOM_JUNGLE -> pick(random, JUNGLE, SMALL_JUNGLE);
                case RANDOM_MUSHROOM -> pick(random, RED_MUSHROOM, BROWN_MUSHROOM, CRIMSON_FUNGUS, WARPED_FUNGUS);
                case RANDOM -> values()[random.nextInt(values().length)].resolve(random);
                default -> this;
            };
        }

        private static Kind pick(Random random, Kind... choices) {
            return choices[random.nextInt(choices.length)].resolve(random);
        }

        boolean grows(Block below) {
            return switch (this) {
                case CHORUS_PLANT -> below == Blocks.END_STONE;
                case RED_MUSHROOM, BROWN_MUSHROOM, CRIMSON_FUNGUS, WARPED_FUNGUS -> FUNGUS_SOIL.contains(below) || SOIL.contains(below);
                default -> SOIL.contains(below);
            };
        }
    }

    private record Placed(int x, int y, int z, BlockState state) {
    }

    private Trees() {
    }

    public static long plant(Edit edit, Map<BlockVec, Kind> requests) {
        Map<Long, List<Map.Entry<BlockVec, Kind>>> byChunk = new HashMap<>();
        for (Map.Entry<BlockVec, Kind> entry : requests.entrySet()) {
            byChunk.computeIfAbsent(ChunkKey.of(entry.getKey().x() >> 4, entry.getKey().z() >> 4), k -> new ArrayList<>()).add(entry);
        }
        List<CompletableFuture<List<List<Placed>>>> parts = new ArrayList<>();
        for (Map.Entry<Long, List<Map.Entry<BlockVec, Kind>>> group : byChunk.entrySet()) {
            int cx = ChunkKey.x(group.getKey());
            int cz = ChunkKey.z(group.getKey());
            parts.add(edit.world().queue().submit(cx, cz, chunk -> {
                List<List<Placed>> grown = new ArrayList<>();
                if (!ownsArea(edit, cx, cz)) {
                    return grown;
                }
                Random random = new Random(ThreadLocalRandom.current().nextLong());
                for (Map.Entry<BlockVec, Kind> request : group.getValue()) {
                    List<Placed> tree = grow(edit, request.getKey(), request.getValue().resolve(random), random);
                    if (!tree.isEmpty()) {
                        grown.add(tree);
                    }
                }
                return grown;
            }));
        }
        long trees = 0;
        for (CompletableFuture<List<List<Placed>>> part : parts) {
            for (List<Placed> tree : Sched.join(part)) {
                for (Placed placed : tree) {
                    edit.set(placed.x(), placed.y(), placed.z(), placed.state());
                }
                trees++;
            }
        }
        return trees;
    }

    private static boolean ownsArea(Edit edit, int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!Sched.owns(edit.world().bukkit(), cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<Placed> grow(Edit edit, BlockVec at, Kind kind, Random random) {
        ServerLevel level = edit.world().level();
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        BlockState here = level.getBlockState(pos);
        Block below = level.getBlockState(pos.below()).getBlock();
        List<Placed> out = new ArrayList<>();
        if (kind == Kind.CHORUS_PLANT) {
            if (below != Blocks.END_STONE) {
                return out;
            }
            capture(edit, at.add(0, -1, 0), kind, random, out);
            return out;
        }
        if (!(here.isAir() || here.canBeReplaced()) || !kind.grows(below)) {
            return out;
        }
        if (kind == Kind.PINE) {
            pine(level, at, random, out);
            return out;
        }
        capture(edit, at, kind, random, out);
        return out;
    }

    private static void capture(Edit edit, BlockVec at, Kind kind, Random random, List<Placed> out) {
        Location location = new Location(edit.world().bukkit(), at.x(), at.y(), at.z());
        edit.world().bukkit().generateTree(location, random, kind.bukkit, (org.bukkit.block.BlockState state) -> {
            out.add(new Placed(state.getX(), state.getY(), state.getZ(), ((CraftBlockState) state).getHandle()));
            return false;
        });
    }

    private static void pine(ServerLevel level, BlockVec base, Random random, List<Placed> out) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        Map<Long, BlockState> placed = new HashMap<>();
        int trunk = random.nextInt(2) + 3;
        int height = random.nextInt(5) + 8;
        for (int i = 0; i < trunk; i++) {
            if (!ifAir(level, placed, base.add(0, i, 0), log, 1, random)) {
                flush(placed, out);
                return;
            }
        }
        BlockVec top = base.add(0, trunk, 0);
        for (int i = 0; i < height; i++) {
            ifAir(level, placed, top.add(0, i, 0), log, 1, random);
            double chance = (i == 0 || i == height - 1) ? 0.6 : 1;
            int[][] inner = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
            for (int[] offset : inner) {
                ifAir(level, placed, top.add(offset[0], i, offset[1]), leaves, chance, random);
            }
            if (i != 0 && i != height - 1) {
                for (int j = -2; j <= 2; j++) {
                    ifAir(level, placed, top.add(-2, i, j), leaves, 0.6, random);
                    ifAir(level, placed, top.add(2, i, j), leaves, 0.6, random);
                    ifAir(level, placed, top.add(j, i, -2), leaves, 0.6, random);
                    ifAir(level, placed, top.add(j, i, 2), leaves, 0.6, random);
                }
            }
        }
        ifAir(level, placed, top.add(0, height, 0), leaves, 1, random);
        flush(placed, out);
    }

    private static boolean ifAir(ServerLevel level, Map<Long, BlockState> placed, BlockVec at, BlockState state,
                                 double chance, Random random) {
        if (random.nextDouble() > chance) {
            return false;
        }
        long key = BlockPos.asLong(at.x(), at.y(), at.z());
        if (placed.containsKey(key) || !level.getBlockState(new BlockPos(at.x(), at.y(), at.z())).isAir()) {
            return false;
        }
        placed.put(key, state);
        return true;
    }

    private static void flush(Map<Long, BlockState> placed, List<Placed> out) {
        for (Map.Entry<Long, BlockState> entry : placed.entrySet()) {
            long key = entry.getKey();
            out.add(new Placed(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key),
                    entry.getValue()));
        }
    }
}
