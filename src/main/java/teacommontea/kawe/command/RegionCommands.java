package teacommontea.kawe.command;

import ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk;
import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import net.kyori.adventure.text.Component;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import teacommontea.kawe.Sched;
import teacommontea.kawe.api.RegionProvider;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.Entities;
import teacommontea.kawe.engine.EntityData;
import teacommontea.kawe.engine.EntityRecord;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.engine.Light;
import teacommontea.kawe.expr.Expression;
import teacommontea.kawe.expr.ExpressionException;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.op.Ops;
import teacommontea.kawe.op.Regen;
import teacommontea.kawe.op.RegionOps;
import teacommontea.kawe.op.Shapes;
import teacommontea.kawe.op.Trees;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.region.ConvexPolyhedralRegion;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.RegionOperationException;
import teacommontea.kawe.region.selector.CuboidSelector;
import teacommontea.kawe.region.selector.Selector;
import teacommontea.kawe.session.Navigation;
import teacommontea.kawe.session.Restrictions;
import teacommontea.kawe.session.Session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class RegionCommands {

    private static final int NBT_LIMIT = 2048;

    private RegionCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("/set").confirm(Confirm.REGION).perm("kawe.region.set").desc("Sets all the blocks in the region")
                .arg("pattern", Types.PATTERN).flag('e', "Also remove the entities in the changed blocks").run(RegionCommands::set));
        Commands.add(Cmd.of("/regen").confirm(Confirm.REGION).perm("kawe.regen").desc("Regenerates the contents of the selection")
                .opt("seed", Types.WORD, "").opt("biome", Types.WORD, "").flag('b', "Regenerate biomes as well").flag('r', "If the seed should be randomized")
                .run(RegionCommands::regen));
        Commands.add(Cmd.of("/wea").aliases("wea").perm("kawe.admin").desc("Bypass region restrictions")
                .player().immediate().run(RegionCommands::wea));
        Commands.add(Cmd.of("/wer").aliases("wer", "select", "/select").perm("kawe.selectregion")
                .desc("Select your current allowed region").player().run(RegionCommands::wer));
        Commands.add(Cmd.of("/air").aliases("/0").confirm(Confirm.REGION).perm("kawe.region.set").desc("Sets all the blocks in the region to air")
                .flag('e', "Also remove the entities in the region").run(RegionCommands::air));
        Commands.add(Cmd.of("/replace").aliases("/re", "/rep").confirm(Confirm.REGION).perm("kawe.region.replace").desc("Replace all blocks in the selection with another")
                .opt("from", Types.MASK, "").arg("to", Types.PATTERN).flag('e', "Also remove the entities in the replaced blocks").run(RegionCommands::replace));
        Commands.add(Cmd.of("/walls").confirm(Confirm.REGION).perm("kawe.region.walls").desc("Build the four sides of the selection")
                .arg("pattern", Types.PATTERN).run(c -> simple(c, Ops::walls, "kawe.walls.changed")));
        Commands.add(Cmd.of("/faces").aliases("/outline").confirm(Confirm.REGION).perm("kawe.region.faces").desc("Build the walls, ceiling, and floor of a selection")
                .arg("pattern", Types.PATTERN).run(c -> simple(c, Ops::faces, "kawe.faces.changed")));
        Commands.add(Cmd.of("/overlay").confirm(Confirm.REGION).perm("kawe.region.overlay").desc("Set a block on top of blocks in the region")
                .arg("pattern", Types.PATTERN).run(c -> simple(c, Ops::overlay, "kawe.overlay.overlaid")));
        Commands.add(Cmd.of("/center").aliases("/middle").perm("kawe.region.center").desc("Set the center block(s)")
                .arg("pattern", Types.PATTERN).run(c -> simple(c, Ops::center, "kawe.center.changed")));
        Commands.add(Cmd.of("/naturalize").confirm(Confirm.REGION).perm("kawe.region.naturalize").desc("3 layers of dirt on top then rock below")
                .run(RegionCommands::naturalize));
        Commands.add(Cmd.of("/move").aliases("/mv").confirm(Confirm.REGION).perm("kawe.region.move").desc("Move the contents of the selection")
                .opt("count", Types.INT, "1").opt("direction", Types.DIRECTION, "me").opt("replace", Types.PATTERN, "air")
                .flag('s', "shift selection").flag('a', "ignore air").flag('e', "entities").flag('b', "biomes").flag('m', "mask", Types.MASK)
                .run(RegionCommands::move));
        Commands.add(Cmd.of("/stack").confirm(Confirm.REGION).perm("kawe.region.stack").desc("Repeat the contents of the selection")
                .opt("count", Types.INT, "1").opt("offset", Types.DIRECTION, "me")
                .flag('s', "shift selection").flag('a', "ignore air").flag('e', "entities").flag('b', "biomes").flag('r', "block units").flag('m', "mask", Types.MASK)
                .run(RegionCommands::stack));
        Commands.add(Cmd.of("/deform").confirm(Confirm.REGION).perm("kawe.region.deform").desc("Deforms a selected region with an expression")
                .arg("expression", Types.TEXT).flag('r', "Use the game's coordinate origin").flag('o', "Use the placement's coordinate origin")
                .flag('c', "Use the selection's center as origin").run(RegionCommands::deform));
        Commands.add(Cmd.of("/line").perm("kawe.region.line").desc("Draws line segments between cuboid selection corners or convex polyhedral selection vertices")
                .arg("pattern", Types.PATTERN).opt("thickness", Types.DOUBLE, "0").flag('h', "Generate only a shell").run(RegionCommands::line));
        Commands.add(Cmd.of("/curve").confirm(Confirm.REGION).perm("kawe.region.curve").desc("Draws a spline through selected points")
                .arg("pattern", Types.PATTERN).opt("thickness", Types.DOUBLE, "0").flag('h', "Generate only a shell").run(RegionCommands::curve));
        Commands.add(Cmd.of("/lay").confirm(Confirm.REGION).perm("kawe.region.overlay").desc("Set the top block in the region")
                .arg("pattern", Types.PATTERN).run(RegionCommands::lay));
        Commands.add(Cmd.of("/smooth").confirm(Confirm.REGION).perm("kawe.region.smooth").desc("Smooth the elevation in the selection")
                .opt("iterations", Types.INT, "1").opt("mask", Types.MASK, "").run(RegionCommands::smooth));
        Commands.add(Cmd.of("/snowsmooth").confirm(Confirm.REGION).perm("kawe.region.snowsmooth").desc("Smooth the elevation in the selection with snow layers")
                .opt("iterations", Types.INT, "1").flag('l', "Set the amount of snow blocks under the snow", Types.INT)
                .flag('m', "The mask of blocks to use as the height map", Types.MASK).run(RegionCommands::snowSmooth));
        Commands.add(Cmd.of("/fall").confirm(Confirm.REGION).perm("kawe.region.fall").desc("Have the blocks in the selection fall")
                .opt("replace", Types.PATTERN, "air").flag('m', "Only fall within the vertical selection").run(RegionCommands::fall));
        Commands.add(Cmd.of("/hollow").confirm(Confirm.REGION).perm("kawe.region.hollow").desc("Hollows out the object contained in this selection")
                .opt("thickness", Types.INT, "0").opt("pattern", Types.PATTERN, "air").flag('m', "Mask to hollow with", Types.MASK)
                .run(RegionCommands::hollow));
        Commands.add(Cmd.of("/forest").confirm(Confirm.REGION).perm("kawe.region.forest").desc("Make a forest within the region")
                .opt("type", TREE, "tree").opt("density", Types.DOUBLE, "5").run(RegionCommands::forest));
        Commands.add(Cmd.of("/flora").confirm(Confirm.REGION).perm("kawe.region.flora").desc("Make flora within the region")
                .opt("density", Types.DOUBLE, "5").run(RegionCommands::flora));
        Commands.add(Cmd.of("/fixblocks").aliases("/updateblocks", "/fixconnect").confirm(Confirm.REGION).perm("kawe.region.fixblocks")
                .desc("Fixes all blocks in the region to the correct shape and connections based on surrounding blocks").run(RegionCommands::fixBlocks));
        Commands.add(Cmd.of("/nbtinfo").aliases("/nbt").perm("kawe.nbtinfo").desc("View nbt info for a block").player().run(RegionCommands::nbtInfo));
        Commands.add(Cmd.of("/removelighting").aliases("/removelight").confirm(Confirm.REGION).perm("kawe.light.remove")
                .desc("Removing lighting in a selection").run(RegionCommands::removeLighting));
        Commands.add(Cmd.of("/setblocklight").aliases("/setlight").perm("kawe.light.set").desc("Set block lighting in a selection")
                .run(c -> c.print("kawe.info.light-blocks")));
        Commands.add(Cmd.of("/setskylight").perm("kawe.light.set").desc("Set sky lighting in a selection")
                .run(c -> c.print("kawe.info.light-blocks")));
        Commands.add(Cmd.of("/fixlighting").confirm(Confirm.REGION).perm("kawe.light.fix").desc("Relight the selected chunks").run(RegionCommands::fixLighting));
    }

    static final ArgType<Trees.Kind> TREE = new ArgType<>() {
        @Override
        public Trees.Kind parse(String raw, CmdContext context) throws InputParseException {
            Trees.Kind kind = Trees.Kind.lookup(raw);
            if (kind == null) {
                throw new InputParseException(Component.text("Unknown tree type: " + raw));
            }
            return kind;
        }

        @Override
        public boolean accepts(String raw) {
            return Trees.Kind.lookup(raw) != null;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Arrays.stream(Trees.Kind.values()).map(kind -> kind.aliases().getFirst()), partial);
        }
    };

    private static void line(CmdContext c) throws Exception {
        Region region = c.selection();
        List<BlockVec> nodes;
        if (region instanceof CuboidRegion cuboid) {
            nodes = List.of(cuboid.pos1(), cuboid.pos2());
        } else if (region instanceof ConvexPolyhedralRegion convex && convex.vertices().size() >= 2) {
            nodes = convex.vertices();
        } else {
            c.print("kawe.line.cuboid-only");
            return;
        }
        double thickness = c.get("thickness", Types.DOUBLE);
        if (thickness < 0) {
            throw new CommandError(Component.text("Thickness must be >= 0"));
        }
        Edit edit = c.edit();
        RegionOps.polyline(edit, nodes, c.get("pattern", Types.PATTERN), thickness, !c.flag('h'), false);
        c.print("kawe.line.changed", c.commit(edit).changed());
    }

    private static void curve(CmdContext c) throws Exception {
        Region region = c.selection();
        if (!(region instanceof ConvexPolyhedralRegion convex)) {
            c.print("kawe.curve.invalid-type");
            return;
        }
        if (convex.vertices().size() < 2) {
            throw new CommandError(Component.text("A curve needs at least two points"));
        }
        double thickness = c.get("thickness", Types.DOUBLE);
        if (thickness < 0) {
            throw new CommandError(Component.text("Thickness must be >= 0"));
        }
        Edit edit = c.edit();
        RegionOps.spline(edit, convex.vertices(), c.get("pattern", Types.PATTERN), 0, 0, 0, 10, thickness, !c.flag('h'));
        c.print("kawe.curve.changed", c.commit(edit).changed());
    }

    private static void lay(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        RegionOps.lay(edit, region, c.get("pattern", Types.PATTERN));
        c.print("kawe.visitor.visitor.block", c.commit(edit).changed());
    }

    private static void smooth(CmdContext c) throws Exception {
        Region region = c.selection();
        int iterations = iterations(c);
        Edit edit = c.edit();
        Mask mask = c.has("mask") ? c.get("mask", Types.MASK) : null;
        RegionOps.smooth(edit, region, iterations, mask);
        c.print("kawe.smooth.changed", c.commit(edit).changed());
    }

    private static void snowSmooth(CmdContext c) throws Exception {
        Region region = c.selection();
        int iterations = iterations(c);
        Integer layers = c.flagValue('l', Types.INT);
        if (layers != null && layers < 0) {
            throw new CommandError(Component.text("Snow layers must be >= 0"));
        }
        Edit edit = c.edit();
        Mask mask = c.flagValue('m', Types.MASK);
        RegionOps.snowSmooth(edit, region, iterations, layers == null ? 1 : layers, mask);
        c.print("kawe.snowsmooth.changed", c.commit(edit).changed());
    }

    private static int iterations(CmdContext c) throws Exception {
        int iterations = c.get("iterations", Types.INT);
        if (iterations < 0) {
            throw new CommandError(Component.text("Iterations must be >= 0"));
        }
        return iterations;
    }

    private static void fall(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        RegionOps.fall(edit, region, !c.flag('m'), c.get("replace", Types.PATTERN));
        c.print("kawe.visitor.visitor.block", c.commit(edit).changed());
    }

    private static void hollow(CmdContext c) throws Exception {
        Region region = c.selection();
        int thickness = c.get("thickness", Types.INT);
        if (thickness < 0) {
            throw new CommandError(Component.text("Thickness must be >= 0"));
        }
        Edit edit = c.edit();
        Mask mask = c.flagValue('m', Types.MASK);
        Mask solid = mask != null ? mask : (x, y, z) -> Masks.solid(edit.get(x, y, z));
        RegionOps.hollow(edit, region, thickness, c.get("pattern", Types.PATTERN), solid);
        c.print("kawe.hollow.changed", c.commit(edit).changed());
    }

    private static void forest(CmdContext c) throws Exception {
        Region region = c.selection();
        double density = c.get("density", Types.DOUBLE);
        if (density < 0 || density > 100) {
            throw new CommandError(Component.text("Density must be in [0, 100]"));
        }
        Edit edit = c.edit();
        long trees = RegionOps.forest(edit, region, density / 100, c.get("type", TREE));
        c.print("kawe.forest.created", trees, c.commit(edit).changed());
    }

    private static void flora(CmdContext c) throws Exception {
        Region region = c.selection();
        double density = c.get("density", Types.DOUBLE);
        if (density < 0 || density > 100) {
            throw new CommandError(Component.text("Density must be in [0, 100]"));
        }
        Edit edit = c.edit();
        RegionOps.flora(edit, region, density / 100);
        c.print("kawe.flora.created", c.commit(edit).changed());
    }

    private static void fixBlocks(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        RegionOps.fixBlocks(edit, region);
        long changed = c.commit(edit).changed();
        if (changed != 0) {
            c.print("kawe.set.done", changed);
        }
    }

    private static void nbtInfo(CmdContext c) throws Exception {
        c.requireActorWorld();
        Edit reader = c.reader();
        reader.captureTiles();
        BlockVec hit = Navigation.blockTrace(reader, c.actor().eye(), c.actor().yaw(), c.actor().pitch(), 128);
        CompoundTag tag = hit == null ? null : reader.tile(hit.x(), hit.y(), hit.z());
        if (tag == null) {
            c.print("kawe.navigation.no.block");
            return;
        }
        String text = tag.toString();
        c.print(Component.text(text.length() > NBT_LIMIT ? text.substring(0, NBT_LIMIT) + "... (" + (text.length() - NBT_LIMIT) + " more characters)" : text));
    }

    private static void fixLighting(CmdContext c) throws Exception {
        Region region = c.selection();
        KWorld world = c.world();
        int shift = Sched.regionChunkShift();
        Map<Long, List<ChunkPos>> groups = new HashMap<>();
        for (long key : region.chunks()) {
            int x = ChunkKey.x(key);
            int z = ChunkKey.z(key);
            groups.computeIfAbsent(ChunkKey.of(x >> shift, z >> shift), k -> new ArrayList<>()).add(new ChunkPos(x, z));
        }
        List<List<ChunkPos>> batches = new ArrayList<>(groups.values());
        long relit = 0;
        while (!batches.isEmpty()) {
            List<CompletableFuture<Relight>> parts = new ArrayList<>();
            for (List<ChunkPos> batch : batches) {
                ChunkPos first = batch.getFirst();
                parts.add(world.queue().submit(first.x(), first.z(), chunk -> relight(world, batch)));
            }
            batches = new ArrayList<>();
            for (CompletableFuture<Relight> part : parts) {
                Relight result = Sched.join(part);
                if (result.done() != null) {
                    relit += Sched.join(result.done());
                }
                for (ChunkPos left : result.unowned()) {
                    batches.add(List.of(left));
                }
            }
        }
        c.print("kawe.info.lighting.propagate.selection", relit);
    }

    private static void removeLighting(CmdContext c) throws Exception {
        Region region = c.selection();
        KWorld world = c.world();
        List<CompletableFuture<Integer>> parts = new ArrayList<>();
        for (long key : region.chunks()) {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            parts.add(world.queue().submit(cx, cz, chunk -> {
                LevelChunk level = world.level().getChunk(cx, cz);
                StarlightChunk light = (StarlightChunk) level;
                BitSet sky = darken(light.starlight$getSkyNibbles());
                BitSet block = darken(light.starlight$getBlockNibbles());
                level.markUnsaved();
                ChunkPos pos = new ChunkPos(cx, cz);
                ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(pos, world.level().getChunkSource().getLightEngine(), sky, block);
                for (ServerPlayer player : world.level().getChunkSource().chunkMap.getPlayers(pos, false)) {
                    player.connection.send(packet);
                }
                return 1;
            }));
        }
        int count = 0;
        for (CompletableFuture<Integer> part : parts) {
            count += Sched.join(part);
        }
        c.print("kawe.info.updated.lighting.selection", count);
    }

    private static BitSet darken(SWMRNibbleArray[] nibbles) {
        BitSet changed = new BitSet();
        if (nibbles == null) {
            return changed;
        }
        for (int i = 0; i < nibbles.length; i++) {
            if (nibbles[i] != null) {
                nibbles[i].setZero();
                nibbles[i].updateVisible();
                changed.set(i);
            }
        }
        return changed;
    }

    private record Relight(CompletableFuture<Integer> done, List<ChunkPos> unowned) {
    }

    private static Relight relight(KWorld world, List<ChunkPos> batch) {
        List<ChunkPos> owned = new ArrayList<>();
        List<ChunkPos> unowned = new ArrayList<>();
        for (ChunkPos pos : batch) {
            boolean ready = batch.size() == 1 || (Sched.owns(world.bukkit(), pos.x(), pos.z())
                    && world.level().getChunkSource().getChunkForLighting(pos.x(), pos.z()) != null);
            (ready ? owned : unowned).add(pos);
        }
        if (owned.isEmpty()) {
            return new Relight(null, unowned);
        }
        CompletableFuture<Integer> done = new CompletableFuture<>();
        Light.relight(world, owned, done::complete);
        return new Relight(done, unowned);
    }

    @FunctionalInterface
    private interface RegionOp {
        long apply(Edit edit, Region region, Pattern pattern);
    }

    private static void simple(CmdContext c, RegionOp op, String key) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        op.apply(edit, region, pattern);
        c.print(key, c.commit(edit).changed());
    }

    private static void set(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Pattern pattern = c.get("pattern", Types.PATTERN);
        edit.stream();
        Ops.setBlocks(edit, region, pattern);
        long affected = commitRemovingEntities(c, edit, region);
        if (affected != 0) {
            c.print("kawe.set.done", affected);
        }
    }

    static long commitRemovingEntities(CmdContext c, Edit edit, Region region) throws Exception {
        if (!c.flag('e')) {
            return c.commit(edit).changed();
        }
        Edit.Result committed = c.commit(edit, true);
        List<EntityRecord> removed = Entities.remove(edit.world(), region,
                edit.allowing(entity -> !(entity instanceof Player) && edit.touchesStaged(entity.getBoundingBox())));
        if (committed.history() != null) {
            committed.history().entitiesRemoved(removed);
        }
        return committed.changed();
    }

    private static void wea(CmdContext c) {
        Session session = c.session();
        boolean bypass = !Restrictions.bypassing(c.actor().player(), session);
        session.bypass(bypass);
        c.print(bypass ? "kawe.info.bypassed" : "kawe.info.restricted");
    }

    private static void wer(CmdContext c) throws Exception {
        KWorld world = c.world();
        Restrictions restrictions = Restrictions.areas(c.actor().player(), world.bukkit());
        RegionProvider.Area area = restrictions == null ? null : restrictions.largest();
        if (area == null) {
            c.print("kawe.error.no.region");
            return;
        }
        Selector next = new CuboidSelector();
        next.selectPrimary(new BlockVec(area.minX(), area.minY(), area.minZ()).clampY(world.minY(), world.maxY()));
        next.selectSecondary(new BlockVec(area.maxX(), area.maxY(), area.maxZ()).clampY(world.minY(), world.maxY()));
        c.session().selector(world.bukkit(), next);
        c.print("kawe.info.set.region");
    }

    private static void regen(CmdContext c) throws Exception {
        Region region = c.selection();
        boolean seeded = c.flag('r');
        Holder<Biome> biome = null;
        for (String raw : new String[]{c.raw("seed"), c.raw("biome")}) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (raw.trim().matches("-?\\d+")) {
                seeded = true;
            } else if (biome == null) {
                biome = BiomeCommands.BIOME.parse(raw.trim(), c);
            } else {
                throw new CommandError(Component.text("Usage: //regen [seed] [biome] [-b] [-r]"));
            }
        }
        if (seeded) {
            throw new CommandError(Component.text("Regen uses the world seed; 26.x world generation cannot regenerate with another seed"));
        }
        ChunkGenerator generator;
        try {
            generator = Regen.generator(c.world(), biome);
        } catch (IllegalArgumentException e) {
            throw new CommandError(Component.text(e.getMessage()));
        }
        c.print("kawe.regen.time");
        Edit edit = c.edit();
        edit.mask(null);
        edit.sourceMask(null);
        try {
            Regen.regenerate(edit, region, generator, c.flag('b'));
        } catch (RuntimeException e) {
            Sched.plugin().getLogger().log(Level.SEVERE, "Regen failed", e);
            c.print("kawe.regen.failed");
            return;
        }
        long changed = c.commit(edit).changed();
        c.unstuck();
        c.print("kawe.regen.regenerated", changed);
    }

    private static void air(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Ops.setBlocks(edit, region, Pattern.of(Blocks.AIR.defaultBlockState()));
        long affected = commitRemovingEntities(c, edit, region);
        if (affected != 0) {
            c.print("kawe.set.done", affected);
        }
    }

    private static void replace(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Mask from = c.has("from") ? c.get("from", Types.MASK) : Masks.existing(edit);
        Pattern to = c.get("to", Types.PATTERN);
        edit.stream();
        Ops.replaceBlocks(edit, region, from, to);
        c.print("kawe.replace.replaced", commitRemovingEntities(c, edit, region));
    }

    private static void naturalize(CmdContext c) throws Exception {
        Region region = c.selection();
        Edit edit = c.edit();
        Ops.naturalize(edit, region);
        c.print("kawe.naturalize.naturalized", c.commit(edit).changed());
    }

    private static void deform(CmdContext c) throws Exception {
        Region region = c.selection();
        GenerationCommands.Origin origin = GenerationCommands.origin(c, region);
        String source = c.get("expression", Types.TEXT);
        Edit edit = c.edit();
        Shapes.Generated result;
        try {
            Expression expression = Expression.compile(source, "x", "y", "z");
            result = Shapes.deform(edit, region, expression, origin.zero(), origin.unit(), c.session().timeout());
        } catch (ExpressionException e) {
            c.errorText(Component.text(e.getMessage()));
            return;
        }
        long affected = c.commit(edit).changed();
        c.unstuck();
        if (result.timedOut() > 0) {
            c.errorText(Component.text(affected + " blocks changed. " + result.timedOut()
                    + " blocks took too long to evaluate and were left unchanged (increase with //timeout)."));
            return;
        }
        c.print("kawe.deform.deformed", affected);
    }

    private static Mask include(CmdContext c, Edit edit) throws Exception {
        Mask mask = c.flagValue('m', Types.MASK);
        if (c.flag('a')) {
            Mask existing = Masks.existing(edit);
            return mask == null ? existing : mask.and(existing);
        }
        return mask;
    }

    private static BlockVec times(BlockVec a, BlockVec b) throws CommandError {
        try {
            return new BlockVec(Math.multiplyExact(a.x(), b.x()), Math.multiplyExact(a.y(), b.y()), Math.multiplyExact(a.z(), b.z()));
        } catch (ArithmeticException e) {
            throw new CommandError(Component.text("The offset is too large"));
        }
    }

    private static Region shifted(CmdContext c, Region region, BlockVec total) throws CommandError {
        try {
            Math.addExact(region.min().x(), total.x());
            Math.addExact(region.max().x(), total.x());
            Math.addExact(region.min().y(), total.y());
            Math.addExact(region.max().y(), total.y());
            Math.addExact(region.min().z(), total.z());
            Math.addExact(region.max().z(), total.z());
        } catch (ArithmeticException e) {
            throw new CommandError(Component.text("The offset is too large"));
        }
        if (!c.flag('s')) {
            return null;
        }
        Region copy = region.copy();
        try {
            copy.shift(total);
        } catch (RegionOperationException e) {
            throw new CommandError(e.component());
        }
        return copy;
    }

    private static void moveEntities(Edit.Result committed, Edit edit, List<EntityRecord> captured, BlockVec step, int count, boolean move) {
        KWorld world = edit.world();
        if (captured.isEmpty()) {
            return;
        }
        if (move) {
            Entities.removeById(world, captured);
        }
        List<Entities.Placement> placements = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Vec3 offset = new Vec3((double) step.x() * i, (double) step.y() * i, (double) step.z() * i);
            for (EntityRecord record : captured) {
                EntityData data = record.data();
                placements.add(new Entities.Placement(data.position().add(offset), data.yaw(), data.pitch(), data.tag(), move ? record.id() : null));
            }
        }
        List<EntityRecord> spawned = Entities.spawn(world, edit.allowed(placements), edit.entityEvents());
        if (committed.history() != null) {
            if (move) {
                committed.history().entitiesRemoved(captured);
            }
            committed.history().entitiesAdded(spawned);
        }
    }

    private static void move(CmdContext c) throws Exception {
        Selector selector = c.selector();
        Region region = selector.region().copy();
        int count = c.get("count", Types.INT);
        if (count < 1) {
            throw new CommandError(Component.text("Multiplier must be >= 1"));
        }
        BlockVec total = times(c.get("direction", Types.DIRECTION), new BlockVec(count, count, count));
        Region next = shifted(c, region, total);
        Edit edit = c.edit();
        Pattern replace = c.get("replace", Types.PATTERN);
        Mask include = include(c, edit);
        List<EntityRecord> captured = c.flag('e') ? edit.allowedRecords(Entities.capture(edit.world(), region)) : List.of();
        Ops.copy(edit, region, total, 1, include, replace, c.flag('b'));
        Edit.Result committed = c.commit(edit, !captured.isEmpty());
        moveEntities(committed, edit, captured, total, 1, true);
        if (next != null) {
            selector.learn(next);
        }
        c.print("kawe.move.moved", committed.changed());
    }

    private static void stack(CmdContext c) throws Exception {
        Selector selector = c.selector();
        Region region = selector.region().copy();
        int count = c.get("count", Types.INT);
        if (count < 1) {
            throw new CommandError(Component.text("Count must be >= 1"));
        }
        BlockVec offset = c.get("offset", Types.DIRECTION);
        BlockVec size;
        try {
            size = new BlockVec(Math.subtractExact(region.max().x(), region.min().x()) + 1,
                    Math.subtractExact(region.max().y(), region.min().y()) + 1,
                    Math.subtractExact(region.max().z(), region.min().z()) + 1);
        } catch (ArithmeticException e) {
            throw new CommandError(Component.text("The selection is too large"));
        }
        BlockVec step = c.flag('r') ? offset : times(offset, size);
        BlockVec total = times(step, new BlockVec(count, count, count));
        Region next = shifted(c, region, total);
        Edit edit = c.edit();
        Mask include = include(c, edit);
        List<EntityRecord> captured = c.flag('e') ? Entities.capture(edit.world(), region) : List.of();
        Ops.copy(edit, region, step, count, include, null, c.flag('b'));
        Edit.Result committed = c.commit(edit, !captured.isEmpty());
        moveEntities(committed, edit, captured, step, count, false);
        if (next != null) {
            selector.learn(next);
        }
        c.print("kawe.stack.changed", committed.changed());
    }
}
