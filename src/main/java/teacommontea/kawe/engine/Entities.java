package teacommontea.kawe.engine;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import org.bukkit.event.entity.CreatureSpawnEvent;
import teacommontea.kawe.Sched;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.region.Region;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;

public final class Entities {

    private Entities() {
    }

    public record Placement(Vec3 position, float yaw, float pitch, CompoundTag tag, UUID id) {
    }

    private static List<Entity> inChunk(ServerLevel level, int chunkX, int chunkZ, Region region) {
        BlockVec min = region.min();
        BlockVec max = region.max();
        double x0 = Math.max(min.x(), chunkX << 4);
        double z0 = Math.max(min.z(), chunkZ << 4);
        double x1 = Math.min(max.x() + 1, (chunkX << 4) + 16);
        double z1 = Math.min(max.z() + 1, (chunkZ << 4) + 16);
        if (x0 >= x1 || z0 >= z1) {
            return List.of();
        }
        AABB box = new AABB(x0, min.y(), z0, x1, max.y() + 1, z1);
        return level.getEntities((Entity) null, box, e -> {
            int bx = (int) Math.floor(e.getX());
            int bz = (int) Math.floor(e.getZ());
            if (e instanceof Player || (bx >> 4) != chunkX || (bz >> 4) != chunkZ || !region.contains(bx, (int) Math.floor(e.getY()), bz)) {
                return false;
            }
            if (!e.isPassenger()) {
                return true;
            }
            Entity root = e.getRootVehicle();
            return !region.contains((int) Math.floor(root.getX()), (int) Math.floor(root.getY()), (int) Math.floor(root.getZ()));
        });
    }

    public static List<EntityRecord> capture(KWorld world, Region region) {
        List<CompletableFuture<List<EntityRecord>>> parts = new ArrayList<>();
        for (long key : region.chunks()) {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            parts.add(world.queue().submit(cx, cz, chunk -> {
                List<EntityRecord> out = new ArrayList<>();
                for (Entity entity : inChunk(world.level(), cx, cz, region)) {
                    TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registries());
                    if (entity.saveAsPassenger(output)) {
                        out.add(new EntityRecord(entity.getUUID(), new EntityData(new Vec3(entity.getX(), entity.getY(), entity.getZ()),
                                entity.getYRot(), entity.getXRot(), output.buildResult())));
                    }
                }
                return out;
            }));
        }
        return Sched.joinAll(parts);
    }

    public static List<EntityRecord> remove(KWorld world, Region region) {
        return remove(world, region, entity -> true);
    }

    public static List<EntityRecord> remove(KWorld world, Region region, Predicate<Entity> filter) {
        List<CompletableFuture<List<EntityRecord>>> parts = new ArrayList<>();
        for (long key : region.chunks()) {
            int cx = ChunkKey.x(key);
            int cz = ChunkKey.z(key);
            parts.add(world.queue().submit(cx, cz, chunk -> discard(world, inChunk(world.level(), cx, cz, region), filter)));
        }
        return Sched.joinAll(parts);
    }

    public static List<EntityRecord> removeAll(KWorld world, Predicate<Entity> filter) {
        Map<Long, List<Entity>> byChunk = new HashMap<>();
        for (Entity entity : ((ChunkSystemLevel) world.level()).moonrise$getEntityLookup().getAllCopy()) {
            byChunk.computeIfAbsent(ChunkKey.of(entity.getBlockX() >> 4, entity.getBlockZ() >> 4), k -> new ArrayList<>()).add(entity);
        }
        List<CompletableFuture<List<EntityRecord>>> parts = new ArrayList<>();
        for (Map.Entry<Long, List<Entity>> entry : byChunk.entrySet()) {
            int cx = ChunkKey.x(entry.getKey());
            int cz = ChunkKey.z(entry.getKey());
            parts.add(world.queue().submit(cx, cz, chunk -> {
                List<Entity> owned = new ArrayList<>();
                for (Entity entity : entry.getValue()) {
                    if (!entity.isRemoved() && !(entity instanceof Player) && !entity.isPassenger()
                            && (entity.getBlockX() >> 4) == cx && (entity.getBlockZ() >> 4) == cz) {
                        owned.add(entity);
                    }
                }
                return discard(world, owned, filter);
            }));
        }
        return Sched.joinAll(parts);
    }

    private static List<EntityRecord> discard(KWorld world, List<Entity> entities, Predicate<Entity> filter) {
        List<EntityRecord> removed = new ArrayList<>();
        for (Entity entity : entities) {
            if (!filter.test(entity)) {
                continue;
            }
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registries());
            if (!entity.saveAsPassenger(output)) {
                Sched.plugin().getLogger().warning("Kept " + EntityType.getKey(entity.getType()) + " at " + entity.blockPosition().toShortString()
                        + ": it cannot be saved, so removing it could not be undone");
                continue;
            }
            removed.add(new EntityRecord(entity.getUUID(), new EntityData(new Vec3(entity.getX(), entity.getY(), entity.getZ()),
                    entity.getYRot(), entity.getXRot(), output.buildResult())));
            entity.discard();
        }
        return removed;
    }

    public static int removeById(KWorld world, List<EntityRecord> records) {
        EntityLookup lookup = ((ChunkSystemLevel) world.level()).moonrise$getEntityLookup();
        List<CompletableFuture<Boolean>> discards = new ArrayList<>();
        for (EntityRecord record : records) {
            Entity entity = lookup.get(record.id());
            if (entity == null || entity instanceof Player) {
                continue;
            }
            discards.add(Sched.onEntity(entity.getBukkitEntity(), () -> {
                if (!entity.isRemoved() && sameType(entity, record)) {
                    entity.discard();
                }
                return true;
            }).exceptionally(error -> {
                Sched.plugin().getLogger().log(Level.WARNING, "Could not remove entity " + record.id(), error);
                return false;
            }));
        }
        int failed = 0;
        for (CompletableFuture<Boolean> discard : discards) {
            if (!Sched.join(discard)) {
                failed++;
            }
        }
        return failed;
    }

    private static boolean addQuietly(KWorld world, Entity root) {
        EntityLookup lookup = ((ChunkSystemLevel) world.level()).moonrise$getEntityLookup();
        if (root.getSelfAndPassengers().anyMatch(e -> world.level().getEntity(e.getUUID()) != null)) {
            return false;
        }
        boolean added = true;
        for (Entity entity : root.getSelfAndPassengers().toList()) {
            entity.spawnReason = CreatureSpawnEvent.SpawnReason.CUSTOM;
            entity.generation = false;
            added &= lookup.addNewEntity(entity, false);
        }
        return added;
    }

    private static boolean sameType(Entity entity, EntityRecord record) {
        String recorded = record.data().tag().getStringOr("id", "");
        return recorded.equals(EntityType.getKey(entity.getType()).toString());
    }

    private static void stripPassengerIds(ServerLevel level, CompoundTag tag) {
        for (Tag passenger : tag.getListOrEmpty("Passengers")) {
            if (passenger instanceof CompoundTag compound) {
                boolean taken = compound.read("UUID", UUIDUtil.CODEC).map(id -> level.getEntity(id) != null).orElse(true);
                if (taken) {
                    compound.remove("UUID");
                }
                stripPassengerIds(level, compound);
            }
        }
    }

    public static List<EntityRecord> respawn(KWorld world, List<EntityRecord> records, boolean events) {
        List<Placement> placements = new ArrayList<>();
        for (EntityRecord record : records) {
            EntityData data = record.data();
            placements.add(new Placement(data.position(), data.yaw(), data.pitch(), data.tag(), record.id()));
        }
        return spawn(world, placements, events);
    }

    public static List<EntityRecord> spawn(KWorld world, List<Placement> placements, boolean events) {
        Map<Long, List<Placement>> byChunk = new HashMap<>();
        for (Placement placement : placements) {
            int cx = (int) Math.floor(placement.position().x()) >> 4;
            int cz = (int) Math.floor(placement.position().z()) >> 4;
            byChunk.computeIfAbsent(ChunkKey.of(cx, cz), k -> new ArrayList<>()).add(placement);
        }
        List<CompletableFuture<List<EntityRecord>>> parts = new ArrayList<>();
        for (Map.Entry<Long, List<Placement>> entry : byChunk.entrySet()) {
            List<Placement> group = entry.getValue();
            parts.add(world.queue().submit(ChunkKey.x(entry.getKey()), ChunkKey.z(entry.getKey()), chunk -> {
                List<EntityRecord> spawned = new ArrayList<>();
                for (Placement placement : group) {
                    CompoundTag tag = placement.tag().copy();
                    tag.remove("UUID");
                    stripPassengerIds(world.level(), tag);
                    Vec3 at = placement.position();
                    Entity entity = EntityType.loadEntityRecursive(
                            TagValueInput.create(ProblemReporter.DISCARDING, world.registries(), tag),
                            world.level(), EntitySpawnReason.COMMAND, e -> {
                                e.snapTo(at.x(), at.y(), at.z(), placement.yaw(), placement.pitch());
                                return e;
                            });
                    if (entity == null) {
                        continue;
                    }
                    UUID wanted = placement.id();
                    UUID id = wanted != null && world.level().getEntity(wanted) == null ? wanted : UUID.randomUUID();
                    entity.setUUID(id);
                    if (events ? world.level().tryAddFreshEntityWithPassengers(entity) : addQuietly(world, entity)) {
                        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registries());
                        CompoundTag saved = entity.saveAsPassenger(output) ? output.buildResult() : placement.tag();
                        spawned.add(new EntityRecord(id, new EntityData(at, placement.yaw(), placement.pitch(), saved)));
                    }
                }
                return spawned;
            }));
        }
        return Sched.joinAll(parts);
    }
}
