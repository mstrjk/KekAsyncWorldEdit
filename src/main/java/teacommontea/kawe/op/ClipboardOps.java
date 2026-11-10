package teacommontea.kawe.op;

import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.clipboard.BlockTransform;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.engine.Entities;
import teacommontea.kawe.engine.EntityData;
import teacommontea.kawe.engine.EntityRecord;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.region.TransformedRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

public final class ClipboardOps {

    private ClipboardOps() {
    }

    public record CopyResult(Clipboard clipboard, long blocks, long biomeColumns, List<EntityRecord> entities) {
    }

    public static CopyResult copy(Edit edit, Region region, BlockVec origin, Mask mask, boolean biomes, boolean entities) {
        edit.captureTiles();
        Clipboard clipboard = new Clipboard(region, origin);
        LongAdder blocks = new LongAdder();
        if (biomes) {
            clipboard.enableBiomes(edit.world().registries().lookupOrThrow(Registries.BIOME));
        }
        Mask sourceMask = edit.sourceMask();
        Mask include = mask != null ? mask : sourceMask;
        Ops.parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            if (include != null && !include.test(x, y, z)) {
                return;
            }
            BlockState state = edit.original(x, y, z);
            clipboard.set(x, y, z, state, state.hasBlockEntity() ? edit.originalTile(x, y, z) : null);
            blocks.increment();
            if (biomes) {
                clipboard.setBiome(x, y, z, edit.originalBiome(x, y, z));
            }
        }));
        long columns = 0;
        if (biomes) {
            for (int x = region.min().x(); x <= region.max().x(); x++) {
                for (int z = region.min().z(); z <= region.max().z(); z++) {
                    if (Ops.flatContains(region, x, z)) {
                        columns++;
                    }
                }
            }
        }
        List<EntityRecord> copied = new ArrayList<>();
        if (entities) {
            for (EntityRecord record : Entities.capture(edit.world(), region)) {
                Vec3 at = record.data().position();
                if (include == null || include.test((int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()))) {
                    copied.add(record);
                }
            }
            List<EntityData> data = new ArrayList<>(copied.size());
            for (EntityRecord record : copied) {
                data.add(record.data());
            }
            clipboard.addEntities(data);
        }
        return new CopyResult(clipboard, blocks.sum(), columns, List.copyOf(copied));
    }

    public static long fill(Edit edit, Region region, Mask mask, teacommontea.kawe.function.Pattern pattern) {
        Mask sourceMask = edit.sourceMask();
        Mask include = mask != null ? mask : sourceMask;
        LongAdder count = new LongAdder();
        Ops.parallelChunks(edit, region, key -> region.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            if ((include == null || include.test(x, y, z)) && pattern.apply(edit, x, y, z)) {
                count.increment();
            }
        }));
        return count.sum();
    }

    public record PasteBounds(BlockVec min, BlockVec max) {
    }

    public static PasteBounds bounds(Clipboard clipboard, Transform transform, BlockVec to) {
        BlockVec min = clipboard.min().subtract(clipboard.origin());
        BlockVec max = clipboard.max().subtract(clipboard.origin());
        double lx = Double.MAX_VALUE;
        double ly = Double.MAX_VALUE;
        double lz = Double.MAX_VALUE;
        double hx = -Double.MAX_VALUE;
        double hy = -Double.MAX_VALUE;
        double hz = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3((i & 1) == 0 ? min.x() : max.x(), (i & 2) == 0 ? min.y() : max.y(), (i & 4) == 0 ? min.z() : max.z());
            Vec3 moved = transform.apply(corner);
            lx = Math.min(lx, moved.x());
            ly = Math.min(ly, moved.y());
            lz = Math.min(lz, moved.z());
            hx = Math.max(hx, moved.x());
            hy = Math.max(hy, moved.y());
            hz = Math.max(hz, moved.z());
        }
        return new PasteBounds(
                new BlockVec((int) Math.floor(lx + 1.0E-6) + to.x(), (int) Math.floor(ly + 1.0E-6) + to.y(), (int) Math.floor(lz + 1.0E-6) + to.z()),
                new BlockVec((int) Math.ceil(hx - 1.0E-6) + to.x(), (int) Math.ceil(hy - 1.0E-6) + to.y(), (int) Math.ceil(hz - 1.0E-6) + to.z()));
    }

    public static long paste(Edit edit, Transform transform, Clipboard clipboard, BlockVec to, boolean pasteAir, boolean biomes, Mask sourceMask) {
        Transform inverse = transform.inverse();
        BlockTransform blocks = new BlockTransform(transform);
        PasteBounds bounds = bounds(clipboard, transform, to);
        if (bounds.max().y() < edit.minY() || bounds.min().y() > edit.maxY()) {
            return 0;
        }
        BlockVec lo = bounds.min().clampY(edit.minY(), edit.maxY());
        BlockVec hi = bounds.max().clampY(edit.minY(), edit.maxY());
        Region target = new CuboidRegion(lo, hi);
        Region source = clipboard.region();
        BlockVec origin = clipboard.origin();
        boolean identity = transform.isIdentity();
        LongAdder count = new LongAdder();
        Ops.parallelChunks(edit, target, key -> target.forEachInChunk(ChunkKey.x(key), ChunkKey.z(key), (x, y, z) -> {
            int sx;
            int sy;
            int sz;
            if (identity) {
                sx = x - to.x() + origin.x();
                sy = y - to.y() + origin.y();
                sz = z - to.z() + origin.z();
            } else {
                Vec3 rel = inverse.apply(new Vec3(x - to.x(), y - to.y(), z - to.z()));
                sx = (int) Math.round(rel.x()) + origin.x();
                sy = (int) Math.round(rel.y()) + origin.y();
                sz = (int) Math.round(rel.z()) + origin.z();
            }
            if (!source.contains(sx, sy, sz)) {
                return;
            }
            if (sourceMask != null && !sourceMask.test(sx, sy, sz)) {
                return;
            }
            if (biomes) {
                Holder<Biome> biome = clipboard.biome(sx, sy, sz);
                if (biome != null) {
                    edit.setBiome(x, y, z, biome);
                }
            }
            BlockState state = clipboard.get(sx, sy, sz);
            if (!pasteAir && state.isAir()) {
                return;
            }
            CompoundTag tile = state.hasBlockEntity() ? clipboard.tile(sx, sy, sz) : null;
            if (edit.set(x, y, z, blocks.apply(state), tile)) {
                count.increment();
            }
        }));
        return count.sum();
    }

    public static Clipboard transformed(Clipboard clipboard, Transform transform) {
        if (transform.isIdentity()) {
            return clipboard;
        }
        BlockVec origin = clipboard.origin();
        PasteBounds bounds = bounds(clipboard, transform, origin);
        Transform inverse = transform.inverse();
        Region source = clipboard.region();
        TransformedRegion shape = new TransformedRegion(source, inverse, origin, bounds.min(), bounds.max());
        Clipboard out = new Clipboard(shape, origin);
        if (clipboard.hasBiomes()) {
            out.enableBiomes(MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME));
        }
        BlockTransform blocks = new BlockTransform(transform);
        BlockVec min = bounds.min();
        BlockVec max = bounds.max();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    BlockVec from = shape.sourceOf(x, y, z);
                    int sx = from.x();
                    int sy = from.y();
                    int sz = from.z();
                    if (!source.contains(sx, sy, sz)) {
                        continue;
                    }
                    BlockState state = clipboard.get(sx, sy, sz);
                    out.set(x, y, z, blocks.apply(state), state.hasBlockEntity() ? clipboard.tile(sx, sy, sz) : null);
                    Holder<Biome> biome = clipboard.biome(sx, sy, sz);
                    if (biome != null) {
                        out.setBiome(x, y, z, biome);
                    }
                }
            }
        }
        Vec3 pivot = origin.center();
        for (EntityData data : clipboard.entities()) {
            Vec3 moved = transform.apply(data.position().subtract(pivot)).add(pivot);
            CompoundTag tag = data.tag().copy();
            transformFacing(tag, transform);
            float[] rotation = orient(transform, data.yaw(), data.pitch());
            out.addEntity(new EntityData(moved, rotation[0], rotation[1], tag));
        }
        return out;
    }

    public static List<EntityRecord> pasteEntities(Edit edit, Transform transform, Clipboard clipboard, BlockVec to) {
        Vec3 pivot = clipboard.origin().center();
        Vec3 target = to.center();
        List<Entities.Placement> placements = new ArrayList<>();
        for (EntityData data : clipboard.entities()) {
            Vec3 moved = transform.apply(data.position().subtract(pivot)).add(target);
            float[] rotation = orient(transform, data.yaw(), data.pitch());
            CompoundTag tag = data.tag().copy();
            transformFacing(tag, transform);
            placements.add(new Entities.Placement(moved, rotation[0], rotation[1], tag, null));
        }
        return Entities.spawn(edit.world(), edit.allowed(placements), edit.entityEvents());
    }

    private static float[] orient(Transform transform, float yaw, float pitch) {
        if (transform.isIdentity()) {
            return new float[] {yaw, pitch};
        }
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        Vec3 flat = transform.applyDirection(new Vec3(-Math.sin(yawRadians), 0, Math.cos(yawRadians)));
        Vec3 look = transform.applyDirection(new Vec3(-Math.sin(yawRadians) * Math.cos(pitchRadians), -Math.sin(pitchRadians),
                Math.cos(yawRadians) * Math.cos(pitchRadians)));
        double horizontal = Math.hypot(flat.x(), flat.z());
        float nextYaw = horizontal < 1.0E-9 ? yaw : (float) Math.toDegrees(Math.atan2(-flat.x(), flat.z()));
        double length = Math.sqrt(look.x() * look.x() + look.y() * look.y() + look.z() * look.z());
        float nextPitch = length < 1.0E-9 ? pitch : (float) Math.toDegrees(Math.asin(Math.clamp(-look.y() / length, -1.0, 1.0)));
        return new float[] {nextYaw, nextPitch};
    }

    private static void transformFacing(CompoundTag tag, Transform transform) {
        if (transform.isIdentity()) {
            return;
        }
        if (tag.contains("Facing")) {
            Direction facing = Direction.from3DDataValue(tag.getByteOr("Facing", (byte) 0));
            Vec3 moved = transform.applyDirection(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()));
            tag.putByte("Facing", (byte) Direction.getApproximateNearest(moved.x(), moved.y(), moved.z()).get3DDataValue());
        }
        if (tag.contains("facing")) {
            Direction facing = Direction.from2DDataValue(tag.getByteOr("facing", (byte) 0));
            Vec3 moved = transform.applyDirection(new Vec3(facing.getStepX(), 0, facing.getStepZ()));
            Direction next = Direction.getApproximateNearest(moved.x(), 0, moved.z());
            if (next.getAxis() != Direction.Axis.Y) {
                tag.putByte("facing", (byte) next.get2DDataValue());
            }
        }
    }
}
