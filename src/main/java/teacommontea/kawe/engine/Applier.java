package teacommontea.kawe.engine;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.storage.TagValueInput;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Tuning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

final class Applier {

    private static final int FLAGS = Block.UPDATE_SKIP_ALL_SIDEEFFECTS;
    private static final int NEIGHBOUR_DEPTH = 512;
    private static final BitSet NO_LIGHT = new BitSet();

    record Applied(int blocks, int biomes, int tileFailures, boolean relight) {
        static final Applied NONE = new Applied(0, 0, 0, false);
    }

    private record Fresh(CompoundTag saved, boolean failed) {
    }

    private Applier() {
    }

    private record Placed(BlockPos pos, BlockState previous, BlockState next) {
    }

    static boolean direct(Set<SideEffect> effects) {
        return !effects.contains(SideEffect.NEIGHBORS) && !effects.contains(SideEffect.UPDATE);
    }

    static Applied apply(KWorld world, LevelChunk chunk, ChunkChanges changes, ChangeSet record, Set<SideEffect> effects, Prepared prepared) {
        if (direct(effects)) {
            return applyDirect(world, chunk, changes, record, prepared);
        }
        ServerLevel level = world.level();
        List<Placed> placed = new ArrayList<>();
        ChangeSet.Chunk history = null;
        int[][] sections = changes.sections();
        Map<Integer, CompoundTag> tiles = changes.tiles();
        int baseX = changes.chunkX << 4;
        int baseZ = changes.chunkZ << 4;
        int blocks = 0;
        int tileFailures = 0;
        for (int s = 0; s < sections.length; s++) {
            int[] section = sections[s];
            if (section == null) {
                continue;
            }
            LevelChunkSection live = chunk.getSection(s);
            int baseY = (s + changes.minSection()) << 4;
            for (int i = 0; i < 4096; i++) {
                int id = section[i];
                if (id == 0) {
                    continue;
                }
                int packed = (s << 12) | i;
                CompoundTag tile = tiles == null ? null : tiles.get(packed);
                BlockState next = Block.stateById(id - 1);
                int lx = i & 15;
                int ly = i >>> 8;
                int lz = (i >>> 4) & 15;
                BlockState previous = live.getBlockState(lx, ly, lz);
                if (previous == next && tile == null && !next.hasBlockEntity()) {
                    continue;
                }
                BlockPos pos = new BlockPos(baseX | lx, baseY | ly, baseZ | lz);
                CompoundTag oldTile = null;
                if (record != null && previous.hasBlockEntity()) {
                    BlockEntity old = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                    oldTile = old == null ? null : old.saveWithoutMetadata(world.registries());
                }
                BlockState actual = next;
                if (previous != next) {
                    chunk.setBlockState(pos, next, FLAGS);
                    actual = live.getBlockState(lx, ly, lz);
                    level.updatePOIOnBlockStateChange(pos, previous, actual);
                    placed.add(new Placed(pos, previous, actual));
                }
                CompoundTag newTile = null;
                if (actual.hasBlockEntity()) {
                    Fresh fresh = freshBlockEntity(world, chunk, pos, actual, tile);
                    newTile = fresh.saved();
                    if (fresh.failed()) {
                        tileFailures++;
                    }
                }
                level.getChunkSource().blockChanged(pos);
                if (record != null) {
                    if (history == null) {
                        history = record.chunk(changes.chunkX, changes.chunkZ);
                    }
                    history.add(packed, Block.getId(previous), Block.getId(actual));
                    if (oldTile != null) {
                        history.oldTile(packed, oldTile);
                    }
                    history.newTile(packed, newTile);
                }
                blocks++;
            }
        }
        physics(world, changes.chunkX, changes.chunkZ, placed, effects);
        return new Applied(blocks, biomes(level, chunk, changes, record, history), tileFailures, false);
    }

    private static int biomes(ServerLevel level, LevelChunk chunk, ChunkChanges changes, ChangeSet record, ChangeSet.Chunk history) {
        Map<Integer, Holder<Biome>> pending = changes.biomes();
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        ChangeSet.Chunk target = history;
        if (record != null && target == null) {
            target = record.chunk(changes.chunkX, changes.chunkZ);
        }
        return applyBiomes(level, chunk, pending, target);
    }

    record PreparedSection(PalettedContainer<BlockState> expected, PalettedContainer<BlockState> states, int[] packed, int[] from, int[] to, int count,
                           int[] special, int specials, int changed) {
    }

    static final class Prepared {
        final PreparedSection[] sections;

        Prepared(PreparedSection[] sections) {
            this.sections = sections;
        }
    }

    static int[] swapRange(ChunkChanges changes) {
        int[][] sections = changes.sections();
        int low = -1;
        int high = -1;
        for (int s = 0; s < sections.length; s++) {
            int[] section = sections[s];
            if (section == null) {
                continue;
            }
            int staged = 0;
            int swapMin = Tuning.get().swapMin();
            for (int i = 0; i < 4096 && staged < swapMin; i++) {
                if (section[i] != 0) {
                    staged++;
                }
            }
            if (staged >= swapMin) {
                if (low < 0) {
                    low = s;
                }
                high = s;
            }
        }
        return low < 0 ? null : new int[] {low, high};
    }

    static Prepared prepare(KWorld world, ChunkChanges changes, ChunkSnap snap) {
        int[][] sections = changes.sections();
        Map<Integer, CompoundTag> tiles = changes.tiles();
        boolean[] poi = poiStates();
        PreparedSection[] out = new PreparedSection[sections.length];
        boolean any = false;
        for (int s = 0; s < sections.length; s++) {
            int[] section = sections[s];
            if (section == null || !snap.captured(s)) {
                continue;
            }
            PreparedSection prepared = prepareSection(world, changes, snap, s, section, tiles, poi);
            if (prepared != null) {
                out[s] = prepared;
                any = true;
            }
        }
        return any ? new Prepared(out) : null;
    }

    private static PreparedSection prepareSection(KWorld world, ChunkChanges changes, ChunkSnap snap, int s, int[] section, Map<Integer, CompoundTag> tiles,
                                                  boolean[] poi) {
        int staged = 0;
        int first = section[0];
        boolean uniform = first != 0;
        for (int i = 0; i < 4096; i++) {
            int id = section[i];
            if (id != 0) {
                staged++;
            }
            if (id != first) {
                uniform = false;
            }
        }
        if (staged < Tuning.get().swapMin()) {
            return null;
        }
        if (uniform && tiles != null) {
            for (int key : tiles.keySet()) {
                if (key >> 12 == s) {
                    uniform = false;
                    break;
                }
            }
        }
        PalettedContainer<BlockState> expected = snap.states(s);
        ServerLevel level = world.level();
        ChunkPos chunkPos = new ChunkPos(changes.chunkX, changes.chunkZ);
        int sectionY = s + changes.minSection();
        PalettedContainer<BlockState> states;
        if (uniform) {
            states = new PalettedContainer<>(Block.stateById(first - 1), level.palettedContainerFactory().blockStatesStrategy(),
                    level.chunkPacketBlockController.getPresetBlockStates(level, chunkPos, sectionY));
        } else {
            states = expected != null ? expected.copy() : level.palettedContainerFactory().createForBlockStates(level, chunkPos, sectionY);
        }
        BlockState air = Blocks.AIR.defaultBlockState();
        int[] packed = new int[staged];
        int[] from = new int[staged];
        int[] to = new int[staged];
        int[] special = new int[16];
        int count = 0;
        int specials = 0;
        int changed = 0;
        for (int i = 0; i < 4096; i++) {
            int id = section[i];
            if (id == 0) {
                continue;
            }
            int key = (s << 12) | i;
            CompoundTag tile = tiles == null ? null : tiles.get(key);
            BlockState next = Block.stateById(id - 1);
            int lx = i & 15;
            int ly = i >>> 8;
            int lz = (i >>> 4) & 15;
            BlockState previous = uniform ? (expected == null ? air : expected.get(lx, ly, lz)) : states.get(lx, ly, lz);
            boolean differs = previous != next;
            if (!differs && tile == null && !next.hasBlockEntity()) {
                continue;
            }
            int previousId = Block.getId(previous);
            if (differs) {
                if (!uniform) {
                    states.getAndSetUnchecked(lx, ly, lz, next);
                }
                changed++;
            }
            packed[count] = key;
            from[count] = previousId;
            to[count] = id - 1;
            count++;
            if (previous.hasBlockEntity() || next.hasBlockEntity() || tile != null || (differs && (poi[previousId] || poi[id - 1]))) {
                if (specials == special.length) {
                    special = Arrays.copyOf(special, specials << 1);
                }
                special[specials++] = i;
            }
        }
        return new PreparedSection(expected, states, packed, from, to, count, special, specials, changed);
    }

    private static boolean sameData(PalettedContainer<BlockState> a, PalettedContainer<BlockState> b) {
        PalettedContainer.Data<BlockState> left = a.data;
        PalettedContainer.Data<BlockState> right = b.data;
        Palette<BlockState> leftPalette = left.palette();
        Palette<BlockState> rightPalette = right.palette();
        int size = leftPalette.getSize();
        if (size != rightPalette.getSize()) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (leftPalette.valueFor(i) != rightPalette.valueFor(i)) {
                return false;
            }
        }
        return Arrays.equals(left.storage().getRaw(), right.storage().getRaw());
    }

    private static boolean matches(LevelChunkSection live, PalettedContainer<BlockState> expected) {
        if (expected == null) {
            return live.hasOnlyAir();
        }
        if (live.hasOnlyAir()) {
            return false;
        }
        PalettedContainer<BlockState> current = live.getStates();
        if (sameData(current, expected)) {
            return true;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (current.get(x, y, z) != expected.get(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static final class Pass {
        final KWorld world;
        final ServerLevel level;
        final LevelChunk chunk;
        final ChunkChanges changes;
        final ChangeSet record;
        final boolean[] poi = poiStates();
        final int bulk = Tuning.get().bulkThreshold();
        final BlockPos[] small = new BlockPos[bulk];
        final boolean[] smallLight = new boolean[bulk];
        ChangeSet.Chunk history;
        int blocks;
        int tileFailures;
        int touched;
        int relit;
        boolean swapped;

        Pass(KWorld world, LevelChunk chunk, ChunkChanges changes, ChangeSet record) {
            this.world = world;
            this.level = world.level();
            this.chunk = chunk;
            this.changes = changes;
            this.record = record;
        }

        ChangeSet.Chunk history() {
            if (history == null) {
                history = record.chunk(changes.chunkX, changes.chunkZ);
            }
            return history;
        }

        BlockPos pos(int s, int i) {
            return new BlockPos((changes.chunkX << 4) | (i & 15), ((s + changes.minSection()) << 4) | (i >>> 8), (changes.chunkZ << 4) | ((i >>> 4) & 15));
        }
    }

    private static Applied applyDirect(KWorld world, LevelChunk chunk, ChunkChanges changes, ChangeSet record, Prepared prepared) {
        LevelLightEngine light = world.level().getChunkSource().getLightEngine();
        Pass pass = new Pass(world, chunk, changes, record);
        int[][] sections = changes.sections();
        for (int s = 0; s < sections.length; s++) {
            int[] section = sections[s];
            if (section == null) {
                continue;
            }
            LevelChunkSection live = chunk.getSection(s);
            boolean wasEmpty = live.hasOnlyAir();
            PreparedSection ready = prepared == null ? null : prepared.sections[s];
            if (ready != null && matches(live, ready.expected())) {
                swapSection(pass, s, live, ready);
            } else {
                blockBySection(pass, s, live, section);
            }
            boolean empty = chunk.getSection(s).hasOnlyAir();
            if (empty != wasEmpty) {
                light.updateSectionStatus(SectionPos.of(changes.chunkX, s + changes.minSection(), changes.chunkZ), empty);
            }
        }
        ServerLevel level = pass.level;
        if (pass.touched > 0) {
            chunk.markUnsaved();
            if (pass.relit > 0) {
                Heightmap.primeHeightmaps(chunk, heightmapTypes(chunk));
            }
            if (pass.touched < pass.bulk && !pass.swapped) {
                for (int i = 0; i < pass.touched; i++) {
                    if (pass.smallLight[i]) {
                        light.checkBlock(pass.small[i]);
                    }
                }
                if (Tuning.get().directSmallPackets()) {
                    sendSmall(level, chunk, pass.small, pass.touched);
                } else {
                    for (int i = 0; i < pass.touched; i++) {
                        level.getChunkSource().blockChanged(pass.small[i]);
                    }
                }
            } else {
                ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, light, NO_LIGHT, NO_LIGHT, true);
                for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
                    player.connection.send(packet);
                }
            }
        }
        boolean relight = pass.relit > 0 && (pass.touched >= pass.bulk || pass.swapped);
        return new Applied(pass.blocks, biomes(level, chunk, changes, record, pass.history), pass.tileFailures, relight);
    }

    private static void sendSmall(ServerLevel level, LevelChunk chunk, BlockPos[] positions, int count) {
        List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false);
        if (players.isEmpty()) {
            return;
        }
        Int2ObjectMap<ShortSet> bySection = new Int2ObjectOpenHashMap<>();
        List<Packet<?>> packets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BlockPos pos = positions[i];
            bySection.computeIfAbsent(SectionPos.blockToSectionCoord(pos.getY()), key -> new ShortOpenHashSet()).add(SectionPos.sectionRelativePos(pos));
        }
        for (Int2ObjectMap.Entry<ShortSet> entry : bySection.int2ObjectEntrySet()) {
            int sectionY = entry.getIntKey();
            ShortSet changed = entry.getValue();
            SectionPos sectionPos = SectionPos.of(chunk.getPos(), sectionY);
            if (changed.size() == 1) {
                packets.add(new ClientboundBlockUpdatePacket(chunk, sectionPos.relativeToBlockPos(changed.iterator().nextShort())));
            } else {
                packets.add(new ClientboundSectionBlocksUpdatePacket(sectionPos, changed, chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY))));
            }
        }
        for (int i = 0; i < count; i++) {
            BlockEntity blockEntity = chunk.getBlockEntity(positions[i]);
            if (blockEntity != null) {
                Packet<?> update = blockEntity.getUpdatePacket();
                if (update != null) {
                    packets.add(update);
                }
            }
        }
        for (ServerPlayer player : players) {
            for (Packet<?> packet : packets) {
                player.connection.send(packet);
            }
        }
    }

    private static void swapSection(Pass pass, int s, LevelChunkSection live, PreparedSection ready) {
        LevelChunk chunk = pass.chunk;
        CompoundTag[] oldTiles = new CompoundTag[ready.specials()];
        BlockState[] previous = new BlockState[ready.specials()];
        for (int k = 0; k < ready.specials(); k++) {
            int i = ready.special()[k];
            int lx = i & 15;
            int ly = i >>> 8;
            int lz = (i >>> 4) & 15;
            BlockState before = live.getBlockState(lx, ly, lz);
            BlockState after = ready.states().get(lx, ly, lz);
            previous[k] = before;
            if (before.hasBlockEntity()) {
                BlockPos pos = pass.pos(s, i);
                if (pass.record != null) {
                    BlockEntity old = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                    oldTiles[k] = old == null ? null : old.saveWithoutMetadata(pass.world.registries());
                }
                if (before != after && (!after.hasBlockEntity() || before.getBlock() != after.getBlock())) {
                    chunk.removeBlockEntity(pos);
                }
            }
        }
        chunk.getSections()[s] = new LevelChunkSection(ready.states(), (PalettedContainer<Holder<Biome>>) live.getBiomes());
        Map<Integer, CompoundTag> tiles = pass.changes.tiles();
        CompoundTag[] newTiles = new CompoundTag[ready.specials()];
        for (int k = 0; k < ready.specials(); k++) {
            int i = ready.special()[k];
            int lx = i & 15;
            int ly = i >>> 8;
            int lz = (i >>> 4) & 15;
            BlockState before = previous[k];
            BlockState after = ready.states().get(lx, ly, lz);
            BlockPos pos = pass.pos(s, i);
            if (before != after && (pass.poi[Block.getId(before)] || pass.poi[Block.getId(after)])) {
                pass.level.updatePOIOnBlockStateChange(pos, before, after);
            }
            if (after.hasBlockEntity()) {
                Fresh fresh = freshBlockEntity(pass.world, chunk, pos, after, tiles == null ? null : tiles.get((s << 12) | i));
                newTiles[k] = fresh.saved();
                if (fresh.failed()) {
                    pass.tileFailures++;
                }
            }
        }
        if (pass.record != null) {
            ChangeSet.Chunk history = pass.history();
            history.addAll(ready.packed(), ready.from(), ready.to(), ready.count());
            for (int k = 0; k < ready.specials(); k++) {
                int key = (s << 12) | ready.special()[k];
                if (oldTiles[k] != null) {
                    history.oldTile(key, oldTiles[k]);
                }
                history.newTile(key, newTiles[k]);
            }
        }
        pass.blocks += ready.count();
        pass.touched += ready.count();
        pass.relit += ready.changed();
        pass.swapped = true;
    }

    private static void blockBySection(Pass pass, int s, LevelChunkSection live, int[] section) {
        LevelChunk chunk = pass.chunk;
        Map<Integer, CompoundTag> tiles = pass.changes.tiles();
        for (int i = 0; i < 4096; i++) {
            int id = section[i];
            if (id == 0) {
                continue;
            }
            int packed = (s << 12) | i;
            CompoundTag tile = tiles == null ? null : tiles.get(packed);
            BlockState next = Block.stateById(id - 1);
            int lx = i & 15;
            int ly = i >>> 8;
            int lz = (i >>> 4) & 15;
            BlockState previous = live.getBlockState(lx, ly, lz);
            boolean changed = previous != next;
            if (!changed && tile == null && !next.hasBlockEntity()) {
                continue;
            }
            BlockPos pos = null;
            int previousId = Block.getId(previous);
            CompoundTag oldTile = null;
            if (previous.hasBlockEntity()) {
                pos = pass.pos(s, i);
                if (pass.record != null) {
                    BlockEntity old = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                    oldTile = old == null ? null : old.saveWithoutMetadata(pass.world.registries());
                }
            }
            if (changed) {
                if (previous.hasBlockEntity() && (!next.hasBlockEntity() || previous.getBlock() != next.getBlock())) {
                    chunk.removeBlockEntity(pos);
                }
                live.setBlockState(lx, ly, lz, next, false);
                if (pass.poi[previousId] || pass.poi[id - 1]) {
                    if (pos == null) {
                        pos = pass.pos(s, i);
                    }
                    pass.level.updatePOIOnBlockStateChange(pos, previous, next);
                }
                pass.relit++;
            }
            CompoundTag newTile = null;
            if (next.hasBlockEntity()) {
                if (pos == null) {
                    pos = pass.pos(s, i);
                }
                Fresh fresh = freshBlockEntity(pass.world, chunk, pos, next, tile);
                newTile = fresh.saved();
                if (fresh.failed()) {
                    pass.tileFailures++;
                }
            }
            if (pass.touched < pass.bulk) {
                pass.small[pass.touched] = pos != null ? pos : pass.pos(s, i);
                pass.smallLight[pass.touched] = changed;
            }
            pass.touched++;
            if (pass.record != null) {
                ChangeSet.Chunk history = pass.history();
                history.add(packed, previousId, id - 1);
                if (oldTile != null) {
                    history.oldTile(packed, oldTile);
                }
                history.newTile(packed, newTile);
            }
            pass.blocks++;
        }
    }

    private static Set<Heightmap.Types> heightmapTypes(LevelChunk chunk) {
        Set<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            types.add(entry.getKey());
        }
        return types;
    }

    private static volatile boolean[] poiStates;

    private static boolean[] poiStates() {
        boolean[] cached = poiStates;
        if (cached != null) {
            return cached;
        }
        boolean[] built = new boolean[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            if (PoiTypes.forState(state).isPresent()) {
                built[Block.getId(state)] = true;
            }
        }
        poiStates = built;
        return built;
    }

    private static void physics(KWorld world, int chunkX, int chunkZ, List<Placed> placed, Set<SideEffect> effects) {
        ServerLevel level = world.level();
        boolean west = Sched.owns(world.bukkit(), chunkX - 1, chunkZ);
        boolean east = Sched.owns(world.bukkit(), chunkX + 1, chunkZ);
        boolean north = Sched.owns(world.bukkit(), chunkX, chunkZ - 1);
        boolean south = Sched.owns(world.bukkit(), chunkX, chunkZ + 1);
        boolean update = effects.contains(SideEffect.UPDATE);
        boolean neighbours = effects.contains(SideEffect.NEIGHBORS);
        boolean events = effects.contains(SideEffect.EVENTS);
        for (Placed change : placed) {
            BlockPos pos = change.pos();
            if (level.getBlockState(pos) != change.next()) {
                continue;
            }
            int lx = pos.getX() & 15;
            int lz = pos.getZ() & 15;
            boolean interior = !((lx == 0 && !west) || (lx == 15 && !east) || (lz == 0 && !north) || (lz == 15 && !south));
            if (update) {
                change.next().onPlace(level, pos, change.previous(), false);
            }
            if (!neighbours) {
                continue;
            }
            if (interior && events) {
                level.updateNeighborsAt(pos, change.previous().getBlock());
            }
            for (Direction direction : Direction.values()) {
                if (!owned(direction, lx, lz, west, east, north, south)) {
                    continue;
                }
                BlockPos side = pos.relative(direction);
                if (!(interior && events)) {
                    level.getBlockState(side).handleNeighborChanged(level, side, change.previous().getBlock(), null, false);
                }
                level.neighborShapeChanged(direction.getOpposite(), side, pos, change.next(), Block.UPDATE_CLIENTS, NEIGHBOUR_DEPTH);
            }
            if (interior) {
                if (change.next().hasAnalogOutputSignal()) {
                    level.updateNeighbourForOutputSignal(pos, change.next().getBlock());
                }
                change.previous().updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, NEIGHBOUR_DEPTH);
                change.next().updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, NEIGHBOUR_DEPTH);
            }
        }
    }

    private static boolean owned(Direction direction, int lx, int lz, boolean west, boolean east, boolean north, boolean south) {
        return switch (direction) {
            case WEST -> lx != 0 || west;
            case EAST -> lx != 15 || east;
            case NORTH -> lz != 0 || north;
            case SOUTH -> lz != 15 || south;
            default -> true;
        };
    }

    private static Fresh freshBlockEntity(KWorld world, LevelChunk chunk, BlockPos pos, BlockState state, CompoundTag tile) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return new Fresh(null, false);
        }
        BlockEntity created = entityBlock.newBlockEntity(pos, state);
        if (created == null) {
            return new Fresh(null, false);
        }
        if (tile != null) {
            try {
                created.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, world.registries(), tile));
            } catch (RuntimeException error) {
                Sched.plugin().getLogger().log(Level.WARNING, "Could not load block entity data at " + pos.toShortString(), error);
                BlockEntity existing = chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK);
                if (existing != null && !existing.isRemoved() && existing.getType().isValid(state)) {
                    return new Fresh(existing.saveWithoutMetadata(world.registries()), true);
                }
                created = entityBlock.newBlockEntity(pos, state);
                if (created == null) {
                    return new Fresh(null, true);
                }
                chunk.addAndRegisterBlockEntity(created);
                created.setChanged();
                return new Fresh(created.saveWithoutMetadata(world.registries()), true);
            }
        }
        chunk.addAndRegisterBlockEntity(created);
        created.setChanged();
        return new Fresh(created.saveWithoutMetadata(world.registries()), false);
    }

    private static int applyBiomes(ServerLevel level, LevelChunk chunk, Map<Integer, Holder<Biome>> biomes, ChangeSet.Chunk history) {
        int changed = 0;
        for (Map.Entry<Integer, Holder<Biome>> entry : biomes.entrySet()) {
            int packed = entry.getKey();
            int s = packed >>> 6;
            if (s < 0 || s >= chunk.getSections().length) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(s);
            int qx = packed & 3;
            int qz = (packed >>> 2) & 3;
            int qy = (packed >>> 4) & 3;
            Holder<Biome> previous = section.getNoiseBiome(qx, qy, qz);
            if (previous.equals(entry.getValue())) {
                continue;
            }
            section.setNoiseBiome(qx, qy, qz, entry.getValue());
            if (history != null) {
                history.biome(packed, previous, entry.getValue());
            }
            changed++;
        }
        if (changed > 0) {
            chunk.markUnsaved();
            ClientboundChunksBiomesPacket packet = ClientboundChunksBiomesPacket.forChunks(List.of(chunk));
            for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
                player.connection.send(packet);
            }
        }
        return changed;
    }
}
