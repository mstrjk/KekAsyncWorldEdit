package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.MinecraftServer;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkDeleter;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.text.PaginationBox;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

public final class ChunkCommands {

    static final ArgType<Long> TIME = (raw, context) -> {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            try {
                return ZonedDateTime.parse(raw).toEpochSecond();
            } catch (DateTimeParseException notZoned) {
                try {
                    return LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toEpochSecond();
                } catch (DateTimeParseException notLocal) {
                    try {
                        return LocalDate.parse(raw).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
                    } catch (DateTimeParseException notDate) {
                        throw new InputParseException(Component.text("Invalid time '" + raw + "'. Use epoch seconds or an ISO date like 2026-01-31 or 2026-01-31T12:00"));
                    }
                }
            }
        }
    };

    private ChunkCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("chunkinfo").aliases("/chunkinfo").perm("kawe.chunkinfo").desc("Get information about the chunk you're inside")
                .player().run(ChunkCommands::info));
        Commands.add(Cmd.of("listchunks").aliases("/listchunks").perm("kawe.listchunks").desc("List chunks that your selection includes")
                .flag('p', "Page number.", Types.INT).run(ChunkCommands::list));
        Commands.add(Cmd.of("delchunks").aliases("/delchunks").confirm(Confirm.ALWAYS).perm("kawe.delchunks").desc("Delete chunks that your selection includes")
                .flag('o', "Only delete chunks older than the specified time.", TIME).run(ChunkCommands::delete));
    }

    private static void info(CmdContext c) {
        BlockVec at = c.actor().block();
        int chunkX = at.x() >> 4;
        int chunkZ = at.z() >> 4;
        String legacy = Integer.toString(chunkX & 63, 36) + "/" + Integer.toString(chunkZ & 63, 36) + "/c." + Integer.toString(chunkX, 36)
                + "." + Integer.toString(chunkZ, 36) + ".dat";
        c.print("kawe.chunkinfo.chunk", chunkX, chunkZ);
        c.print("kawe.chunkinfo.old-filename", legacy);
        c.print("kawe.chunkinfo.mcregion-filename", "r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
    }

    private static void list(CmdContext c) throws Exception {
        Region region = c.selection();
        Integer page = c.flagValue('p', Types.INT);
        PaginationBox box;
        if (region instanceof CuboidRegion) {
            int minX = region.min().x() >> 4;
            int minZ = region.min().z() >> 4;
            int width = (region.max().x() >> 4) - minX + 1;
            long total = (long) width * ((region.max().z() >> 4) - minZ + 1);
            if (total > Integer.MAX_VALUE) {
                throw new CommandError(Component.text("Selection covers too many chunks to list"));
            }
            box = new PaginationBox("Selected Chunks", "/listchunks -p %page%", (int) total,
                    i -> Component.text("(" + (minX + i % width) + ", " + (minZ + i / width) + ")"));
        } else {
            long[] keys = region.chunks().stream().mapToLong(Long::longValue).toArray();
            box = new PaginationBox("Selected Chunks", "/listchunks -p %page%", keys.length,
                    i -> Component.text("(" + ChunkKey.x(keys[i]) + ", " + ChunkKey.z(keys[i]) + ")"));
        }
        c.print("kawe.listchunks.listfor", c.actor().name());
        try {
            c.print(box.forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    private static void delete(CmdContext c) throws Exception {
        Region region = c.selection();
        Path world = MinecraftServer.getServer().storageSource.getDimensionPath(c.world().level().dimension());
        Path file = Sched.plugin().getDataFolder().toPath().resolve(ChunkDeleter.FILE_NAME);
        ChunkDeleter.Batch batch = new ChunkDeleter.Batch();
        batch.world = world.toAbsolutePath().normalize().toString();
        batch.before = c.flagValue('o', TIME);
        if (region instanceof CuboidRegion) {
            batch.min = new int[]{region.min().x() >> 4, region.min().z() >> 4};
            batch.max = new int[]{region.max().x() >> 4, region.max().z() >> 4};
            batch.chunks = null;
        } else {
            for (long key : region.chunks()) {
                batch.chunks.add(new int[]{ChunkKey.x(key), ChunkKey.z(key)});
            }
        }
        ChunkDeleter.Info info = ChunkDeleter.append(file, batch);
        c.print(Component.text(batch.count() + " chunk(s) have been marked for deletion the next time the server starts."));
        if (info.batches.size() > 1) {
            long total = info.batches.stream().mapToLong(ChunkDeleter.Batch::count).sum();
            c.print(Component.text(total + " chunks total marked for deletion. (May have overlaps).", NamedTextColor.GRAY));
        }
        c.actor().print(Component.text("You can mark more chunks for deletion, or to stop now, run: ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("/stop", NamedTextColor.AQUA).clickEvent(ClickEvent.suggestCommand("/stop"))));
    }
}
