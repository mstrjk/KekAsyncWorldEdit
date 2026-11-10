package teacommontea.kawe.engine;

import net.minecraft.nbt.CompoundTag;
import teacommontea.kawe.math.Vec3;

public record EntityData(Vec3 position, float yaw, float pitch, CompoundTag tag) {
}
