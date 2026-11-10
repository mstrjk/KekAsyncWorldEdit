package teacommontea.kawe.op;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import teacommontea.kawe.Sched;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.parse.InputParseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class ItemUse {

    private static final GameProfile PROFILE = new GameProfile(UUID.nameUUIDFromBytes("kawe".getBytes(StandardCharsets.UTF_8)), "[KAWE]");
    private static final ThreadLocal<Map<ServerLevel, FakePlayer>> PLAYERS = ThreadLocal.withInitial(WeakHashMap::new);

    private ItemUse() {
    }

    public record Result(int used, int failed) {
    }

    public static ItemStack parse(String raw) throws InputParseException {
        try {
            return new ItemParser(MinecraftServer.getServer().registryAccess()).parse(new StringReader(raw.trim())).createItemStack(1);
        } catch (CommandSyntaxException e) {
            throw new InputParseException(Component.text(e.getMessage()));
        }
    }

    public static Direction face(BlockVec direction) throws InputParseException {
        for (Direction face : Direction.values()) {
            if (face.getStepX() == direction.x() && face.getStepY() == direction.y() && face.getStepZ() == direction.z()) {
                return face;
            }
        }
        throw new InputParseException(Component.text("Item use needs one of up, down, north, south, east, west"));
    }

    public static Result use(KWorld world, List<BlockVec> positions, ItemStack stack, Direction face) {
        Map<Long, List<BlockVec>> byChunk = new HashMap<>();
        for (BlockVec at : positions) {
            byChunk.computeIfAbsent(ChunkKey.of(at.x() >> 4, at.z() >> 4), k -> new ArrayList<>()).add(at);
        }
        List<CompletableFuture<int[]>> futures = new ArrayList<>(byChunk.size());
        for (Map.Entry<Long, List<BlockVec>> entry : byChunk.entrySet()) {
            List<BlockVec> spots = entry.getValue();
            futures.add(world.queue().submit(ChunkKey.x(entry.getKey()), ChunkKey.z(entry.getKey()), chunk -> {
                ServerLevel level = world.level();
                int[] counts = new int[2];
                for (BlockVec at : spots) {
                    try {
                        if (useOne(level, new BlockPos(at.x(), at.y(), at.z()), stack, face)) {
                            counts[0]++;
                        }
                    } catch (RuntimeException e) {
                        if (counts[1]++ == 0) {
                            Sched.plugin().getLogger().log(Level.WARNING, "Item use failed at " + at, e);
                        }
                    }
                }
                return counts;
            }));
        }
        int used = 0;
        int failed = 0;
        for (CompletableFuture<int[]> future : futures) {
            int[] counts = Sched.join(future);
            used += counts[0];
            failed += counts[1];
        }
        return new Result(used, failed);
    }

    private static boolean useOne(ServerLevel level, BlockPos pos, ItemStack stack, Direction face) {
        if (level.getBlockState(pos).isAir()) {
            return false;
        }
        FakePlayer player = PLAYERS.get().computeIfAbsent(level, FakePlayer::new);
        ItemStack held = stack.copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        float pitch = face == Direction.UP ? 90 : face == Direction.DOWN ? -90 : 0;
        float yaw = face.getAxis().isHorizontal() ? face.getOpposite().toYRot() : 0;
        Vec3 feet = Vec3.atBottomCenterOf(pos.relative(face));
        player.snapTo(feet.x, feet.y, feet.z, yaw, pitch);
        BlockHitResult ray = new BlockHitResult(hit, face, pos, false);
        InteractionResult result = held.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, ray));
        if (!result.consumesAction()) {
            result = level.getBlockState(pos).useItemOn(held, level, player, InteractionHand.MAIN_HAND, ray);
        }
        if (!result.consumesAction()) {
            result = held.getItem().use(level, player, InteractionHand.MAIN_HAND);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return result.consumesAction();
    }

    private static final class FakePlayer extends ServerPlayer {
        FakePlayer(ServerLevel level) {
            super(level.getServer(), level, PROFILE, ClientInformation.createDefault());
        }

        @Override
        public OptionalInt openMenu(MenuProvider provider) {
            return OptionalInt.empty();
        }
    }
}
