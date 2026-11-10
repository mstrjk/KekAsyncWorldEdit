package teacommontea.kawe.api;

import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

public interface RegionProvider {

    List<Area> allowed(Player player, World world);

    record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public Area {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Area minimum must not exceed its maximum");
            }
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }
}
