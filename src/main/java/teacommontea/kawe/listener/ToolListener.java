package teacommontea.kawe.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.session.Session;
import teacommontea.kawe.session.Sessions;
import teacommontea.kawe.tool.Tool;
import teacommontea.kawe.tool.ToolClick;
import teacommontea.kawe.tool.ToolTag;

import java.util.Locale;

public final class ToolListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !ToolTag.usable(item)) {
            return;
        }
        Player player = event.getPlayer();
        Session session = Sessions.of(player);
        boolean left = action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR;
        Tool tool = session.tool(item.getType());
        if (tool == null && left && session.superPickaxe() && action == Action.LEFT_CLICK_BLOCK && pickaxe(item.getType())) {
            tool = session.superPickaxeTool();
        }
        if (tool == null || (tool.permission() != null && !player.hasPermission(tool.permission()))) {
            return;
        }
        Block clicked = event.getClickedBlock();
        ToolClick click = new ToolClick(player, session, clicked == null ? null : new BlockVec(clicked.getX(), clicked.getY(), clicked.getZ()), event.getBlockFace());
        if (left ? tool.leftClick(click) : tool.rightClick(click)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        ItemStack item = player.getInventory().getItem(event.getPreviousSlot());
        if (item == null || !ToolTag.usable(item)) {
            return;
        }
        Session session = Sessions.of(player);
        Tool tool = session.tool(item.getType());
        if (tool == null || (tool.permission() != null && !player.hasPermission(tool.permission()))) {
            return;
        }
        int distance = event.getNewSlot() - event.getPreviousSlot();
        if (Math.abs(distance) > 4) {
            distance = distance > 0 ? distance - 9 : distance + 9;
        }
        int amount = Integer.signum(-distance);
        if (amount != 0 && tool.scroll(new ToolClick(player, session, null, null), amount)) {
            event.setCancelled(true);
        }
    }

    private static boolean pickaxe(Material type) {
        return type.name().toLowerCase(Locale.ROOT).endsWith("_pickaxe");
    }
}
