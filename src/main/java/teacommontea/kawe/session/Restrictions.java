package teacommontea.kawe.session;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import teacommontea.kawe.Settings;
import teacommontea.kawe.api.RegionProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class Restrictions {

    public static final String BYPASS = "kawe.bypass.regions";

    private final List<RegionProvider.Area> areas;

    private Restrictions(List<RegionProvider.Area> areas) {
        this.areas = List.copyOf(areas);
    }

    public static Restrictions of(Player player, Session session, World world) {
        if (player == null || !Settings.get().regionRestrictions() || bypassing(player, session)) {
            return null;
        }
        return areas(player, world);
    }

    public static Restrictions areas(Player player, World world) {
        Collection<RegisteredServiceProvider<RegionProvider>> providers = Bukkit.getServicesManager().getRegistrations(RegionProvider.class);
        if (providers.isEmpty()) {
            return null;
        }
        List<RegionProvider.Area> out = new ArrayList<>();
        for (RegisteredServiceProvider<RegionProvider> provider : providers) {
            List<RegionProvider.Area> allowed = provider.getProvider().allowed(player, world);
            if (allowed != null) {
                out.addAll(allowed);
            }
        }
        return new Restrictions(out);
    }

    public static boolean bypassing(Player player, Session session) {
        Boolean override = session.bypass();
        return override != null ? override : player.hasPermission(BYPASS);
    }

    public boolean isEmpty() {
        return areas.isEmpty();
    }

    public boolean allows(int x, int y, int z) {
        for (RegionProvider.Area area : areas) {
            if (area.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    public RegionProvider.Area largest() {
        return areas.stream().max(Comparator.comparingLong(RegionProvider.Area::volume)).orElse(null);
    }
}
