package teacommontea.kawe.session;

import net.kyori.adventure.text.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import teacommontea.kawe.engine.KWorld;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.text.Msg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class Actor {

    public record HandBlock(BlockState state, CompoundTag nbt) {
    }

    private final CommandSender sender;
    private final Player player;
    private final String name;
    private final UUID uuid;
    private final boolean console;
    private final boolean op;
    private final Map<String, Boolean> permissions;
    private final KWorld world;
    private final Location location;
    private final Vec3 eye;
    private final List<Block> hotbar;
    private final HandBlock hand;
    private final HandBlock offhand;
    private final Material handType;
    private final Locale locale;
    private final boolean quiet;

    private Actor(Actor base, Location at, boolean quiet) {
        this.sender = base.sender;
        this.player = base.player;
        this.name = base.name;
        this.uuid = base.uuid;
        this.console = base.console;
        this.op = base.op;
        this.permissions = base.permissions;
        this.world = base.world;
        this.location = at.clone();
        double eyeHeight = base.eye == null || base.location == null ? 0 : base.eye.y() - base.location.getY();
        this.eye = new Vec3(at.getX(), at.getY() + eyeHeight, at.getZ());
        this.hotbar = base.hotbar;
        this.hand = base.hand;
        this.offhand = base.offhand;
        this.handType = base.handType;
        this.locale = base.locale;
        this.quiet = quiet;
    }

    public Actor relocated(Location at, boolean silent) {
        return new Actor(this, at, silent);
    }

    private Actor(CommandSender sender) {
        this.quiet = false;
        this.sender = sender;
        this.player = sender instanceof Player p ? p : null;
        this.name = sender.getName();
        this.console = sender instanceof ConsoleCommandSender;
        this.op = sender.isOp();
        Map<String, Boolean> perms = new HashMap<>();
        if (!console) {
            for (PermissionAttachmentInfo info : sender.getEffectivePermissions()) {
                perms.put(info.getPermission().toLowerCase(Locale.ROOT), info.getValue());
            }
        }
        this.permissions = Map.copyOf(perms);
        if (player != null) {
            this.uuid = player.getUniqueId();
            this.world = KWorld.of(player.getWorld());
            this.location = player.getLocation();
            Location eyes = player.getEyeLocation();
            this.eye = new Vec3(eyes.getX(), eyes.getY(), eyes.getZ());
            ItemStack main = player.getInventory().getItemInMainHand();
            this.handType = main.getType();
            this.hand = blockOf(main);
            this.offhand = blockOf(player.getInventory().getItemInOffHand());
            this.locale = player.locale();
            List<Block> slots = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                HandBlock block = blockOf(player.getInventory().getItem(slot));
                if (block != null) {
                    slots.add(block.state().getBlock());
                }
            }
            this.hotbar = List.copyOf(slots);
        } else {
            this.uuid = null;
            this.world = null;
            this.location = null;
            this.eye = null;
            this.handType = Material.AIR;
            this.hand = null;
            this.offhand = null;
            this.locale = Locale.ROOT;
            this.hotbar = List.of();
        }
    }

    public static Actor capture(CommandSender sender) {
        return new Actor(sender);
    }

    private static HandBlock blockOf(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        net.minecraft.world.item.ItemStack stack = CraftItemStack.asNMSCopy(item);
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        BlockItemStateProperties properties = stack.get(DataComponents.BLOCK_STATE);
        if (properties != null) {
            state = properties.apply(state);
        }
        TypedEntityData<BlockEntityType<?>> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        CompoundTag nbt = data == null ? null : data.copyTagWithoutId();
        return new HandBlock(state, nbt == null || nbt.isEmpty() ? null : nbt);
    }

    public CommandSender sender() {
        return sender;
    }

    public Player player() {
        return player;
    }

    public List<Block> hotbarBlocks() {
        return hotbar;
    }

    public Locale locale() {
        return locale;
    }

    public boolean isPlayer() {
        return player != null;
    }

    public UUID uuid() {
        return uuid;
    }

    public KWorld kworld() {
        return world;
    }

    public Location location() {
        return location == null ? null : location.clone();
    }

    public BlockVec block() {
        return location == null ? null : new BlockVec(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public Vec3 eye() {
        return eye;
    }

    public Vec3 position() {
        return location == null ? null : new Vec3(location.getX(), location.getY(), location.getZ());
    }

    public float yaw() {
        return location == null ? 0 : location.getYaw();
    }

    public float pitch() {
        return location == null ? 0 : location.getPitch();
    }

    public BlockFace facing() {
        return location == null ? BlockFace.NORTH : yawFace(location.getYaw());
    }

    public static BlockFace yawFace(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    public HandBlock hand() {
        return hand;
    }

    public HandBlock offhand() {
        return offhand;
    }

    public Material handType() {
        return handType;
    }

    public String name() {
        return name;
    }

    public boolean has(String permission) {
        if (console) {
            return true;
        }
        String node = permission.toLowerCase(Locale.ROOT);
        Boolean exact = permissions.get(node);
        if (exact != null) {
            return exact;
        }
        String parent = node;
        int dot;
        while ((dot = parent.lastIndexOf('.')) > 0) {
            parent = parent.substring(0, dot);
            Boolean wildcard = permissions.get(parent + ".*");
            if (wildcard != null) {
                return wildcard;
            }
        }
        Boolean all = permissions.get("*");
        if (all != null) {
            return all;
        }
        Permission registered = Bukkit.getPluginManager().getPermission(node);
        return (registered != null ? registered.getDefault() : Permission.DEFAULT_PERMISSION).getValue(op);
    }

    public void print(String key, Object... args) {
        if (quiet) {
            return;
        }
        Msg.print(sender, key, args);
    }

    public void info(String key, Object... args) {
        if (quiet) {
            return;
        }
        Msg.info(sender, key, args);
    }

    public void error(String key, Object... args) {
        Msg.error(sender, key, args);
    }

    public void debug(String key, Object... args) {
        Msg.debug(sender, key, args);
    }

    public void print(Component message) {
        if (quiet) {
            return;
        }
        Msg.print(sender, message);
    }

    public void infoText(Component message) {
        if (quiet) {
            return;
        }
        Msg.infoText(sender, message);
    }

    public void errorText(Component message) {
        Msg.errorText(sender, message);
    }
}
