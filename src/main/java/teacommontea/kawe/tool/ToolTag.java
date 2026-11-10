package teacommontea.kawe.tool;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import teacommontea.kawe.Settings;

public final class ToolTag {

    private static final String VALUE = "KekAsyncWorldEdit";
    private static NamespacedKey key;

    private ToolTag() {
    }

    public static void init(Plugin plugin) {
        key = new NamespacedKey(plugin, "tool");
    }

    public static boolean usable(ItemStack item) {
        return !Settings.get().pdcToolTag() || item.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public static void tag(ItemStack item) {
        if (Settings.get().pdcToolTag() && !item.getType().isAir()) {
            item.editPersistentDataContainer(data -> data.set(key, PersistentDataType.STRING, VALUE));
        }
    }
}
