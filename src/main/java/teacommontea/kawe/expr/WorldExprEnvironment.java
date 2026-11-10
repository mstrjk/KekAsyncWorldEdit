package teacommontea.kawe.expr;

import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.engine.Extent;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.schematic.Legacy;

public final class WorldExprEnvironment implements ExprEnvironment {

    private final Extent extent;
    private final Vec3 unit;
    private final Vec3 zero;
    private final ThreadLocal<Vec3> current = ThreadLocal.withInitial(() -> Vec3.ZERO);

    public WorldExprEnvironment(Extent extent, Vec3 unit, Vec3 zero) {
        this.extent = extent;
        this.unit = unit;
        this.zero = zero.add(0.5, 0.5, 0.5);
    }

    public void current(double x, double y, double z) {
        current.set(new Vec3(x, y, z));
    }

    private BlockState scaled(double x, double y, double z) {
        return at(x * unit.x() + zero.x(), y * unit.y() + zero.y(), z * unit.z() + zero.z());
    }

    private BlockState at(double x, double y, double z) {
        return extent.get((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private BlockState relative(double x, double y, double z) {
        Vec3 base = current.get();
        return at(base.x() + x, base.y() + y, base.z() + z);
    }

    private static int combined(BlockState state) {
        return Legacy.combined(state.getBlock());
    }

    @Override
    public int blockType(double x, double y, double z) {
        return combined(scaled(x, y, z)) >> 4;
    }

    @Override
    public int blockData(double x, double y, double z) {
        return combined(scaled(x, y, z)) & 0xF;
    }

    @Override
    public int blockTypeAbs(double x, double y, double z) {
        return combined(at(x, y, z)) >> 4;
    }

    @Override
    public int blockDataAbs(double x, double y, double z) {
        return combined(at(x, y, z)) & 0xF;
    }

    @Override
    public int blockTypeRel(double x, double y, double z) {
        return combined(relative(x, y, z)) >> 4;
    }

    @Override
    public int blockDataRel(double x, double y, double z) {
        return combined(relative(x, y, z)) & 0xF;
    }
}
