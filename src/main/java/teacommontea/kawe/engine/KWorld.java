package teacommontea.kawe.engine;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

public final class KWorld {

    private final World world;
    private final ServerLevel level;
    private final int minY;
    private final int maxY;
    private final int minSection;
    private final int sections;

    private KWorld(World world) {
        this.world = world;
        this.level = ((CraftWorld) world).getHandle();
        this.minY = level.getMinY();
        this.maxY = level.getMinY() + level.getHeight() - 1;
        this.minSection = minY >> 4;
        this.sections = (maxY >> 4) - minSection + 1;
    }

    public static KWorld of(World world) {
        return new KWorld(world);
    }

    public World bukkit() {
        return world;
    }

    public ServerLevel level() {
        return level;
    }

    public RegistryAccess registries() {
        return level.registryAccess();
    }

    public String name() {
        return world.getName();
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int minSection() {
        return minSection;
    }

    public int sections() {
        return sections;
    }

    public RegionQueue queue() {
        return RegionQueue.of(world);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KWorld other && other.world.equals(world);
    }

    @Override
    public int hashCode() {
        return world.hashCode();
    }
}
