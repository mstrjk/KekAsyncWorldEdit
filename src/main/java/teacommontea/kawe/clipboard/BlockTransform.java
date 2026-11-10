package teacommontea.kawe.clipboard;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import teacommontea.kawe.math.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockTransform {

    private static final double EPSILON = 1.0E-6;

    private final Transform transform;
    private final boolean exact;
    private final boolean horizontal;
    private final boolean flipY;
    private final Mirror mirror;
    private final Rotation rotation;
    private final Map<Direction, Direction> directions = new EnumMap<>(Direction.class);
    private final ConcurrentHashMap<BlockState, BlockState> cache = new ConcurrentHashMap<>();

    public BlockTransform(Transform transform) {
        this.transform = transform;
        this.exact = isAxisPermutation(transform);
        for (Direction direction : Direction.values()) {
            Vec3 moved = transform.applyDirection(new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
            directions.put(direction, Direction.getApproximateNearest(moved.x(), moved.y(), moved.z()));
        }
        Direction up = directions.get(Direction.UP);
        boolean yAxisKept = exact && up.getAxis() == Direction.Axis.Y;
        this.horizontal = yAxisKept;
        this.flipY = yAxisKept && up == Direction.DOWN;
        if (yAxisKept) {
            Direction east = directions.get(Direction.EAST);
            Direction south = directions.get(Direction.SOUTH);
            boolean mirrored = east.getClockWise() != south;
            Direction eastAfterMirror = mirrored ? east.getOpposite() : east;
            this.mirror = mirrored ? Mirror.FRONT_BACK : Mirror.NONE;
            this.rotation = switch (eastAfterMirror) {
                case SOUTH -> Rotation.CLOCKWISE_90;
                case WEST -> Rotation.CLOCKWISE_180;
                case NORTH -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };
        } else {
            this.mirror = Mirror.NONE;
            this.rotation = Rotation.NONE;
        }
    }

    private static boolean isAxisPermutation(Transform t) {
        double[][] m = {
                {t.m00(), t.m01(), t.m02()},
                {t.m10(), t.m11(), t.m12()},
                {t.m20(), t.m21(), t.m22()}
        };
        for (int row = 0; row < 3; row++) {
            int ones = 0;
            for (int col = 0; col < 3; col++) {
                double v = m[row][col];
                if (Math.abs(Math.abs(v) - 1) < EPSILON) {
                    ones++;
                } else if (Math.abs(v) > EPSILON) {
                    return false;
                }
            }
            if (ones != 1) {
                return false;
            }
        }
        return Math.abs(Math.abs(t.determinant()) - 1) < EPSILON;
    }

    public boolean exact() {
        return exact;
    }

    public BlockState apply(BlockState state) {
        if (transform.isIdentity()) {
            return state;
        }
        return cache.computeIfAbsent(state, this::compute);
    }

    private BlockState compute(BlockState state) {
        if (horizontal) {
            BlockState out = state.mirror(mirror).rotate(rotation);
            return flipY ? flipVertical(out) : out;
        }
        if (state.getBlock() instanceof StairBlock) {
            return stairs(state);
        }
        BlockState out = permuteConnections(state);
        for (Property<?> property : state.getProperties()) {
            out = transformProperty(out, property);
        }
        return out;
    }

    private BlockState flipVertical(BlockState state) {
        BlockState out = state;
        if (out.hasProperty(BlockStateProperties.HALF)) {
            out = out.setValue(BlockStateProperties.HALF, out.getValue(BlockStateProperties.HALF) == Half.TOP ? Half.BOTTOM : Half.TOP);
        }
        if (out.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType type = out.getValue(BlockStateProperties.SLAB_TYPE);
            if (type != SlabType.DOUBLE) {
                out = out.setValue(BlockStateProperties.SLAB_TYPE, type == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP);
            }
        }
        if (out.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            out = out.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                    out.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER);
        }
        if (out.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            AttachFace face = out.getValue(BlockStateProperties.ATTACH_FACE);
            if (face != AttachFace.WALL) {
                out = out.setValue(BlockStateProperties.ATTACH_FACE, face == AttachFace.FLOOR ? AttachFace.CEILING : AttachFace.FLOOR);
            }
        }
        if (out.hasProperty(BlockStateProperties.BELL_ATTACHMENT)) {
            BellAttachType bell = out.getValue(BlockStateProperties.BELL_ATTACHMENT);
            if (bell == BellAttachType.FLOOR || bell == BellAttachType.CEILING) {
                out = out.setValue(BlockStateProperties.BELL_ATTACHMENT, bell == BellAttachType.FLOOR ? BellAttachType.CEILING : BellAttachType.FLOOR);
            }
        }
        if (out.hasProperty(BlockStateProperties.HANGING)) {
            out = out.setValue(BlockStateProperties.HANGING, !out.getValue(BlockStateProperties.HANGING));
        }
        out = swapUpDown(out);
        for (Property<?> property : out.getProperties()) {
            if (property.getValueClass() == Direction.class) {
                Direction current = (Direction) out.getValue(property);
                if (current.getAxis() == Direction.Axis.Y) {
                    out = setDirection(out, property, current.getOpposite());
                }
            }
        }
        return out;
    }

    private static BlockState swapUpDown(BlockState state) {
        Property<?> up = null;
        Property<?> down = null;
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("up")) {
                up = property;
            } else if (property.getName().equals("down")) {
                down = property;
            }
        }
        if (up == null || down == null || up.getValueClass() != down.getValueClass()) {
            return state;
        }
        String upValue = valueName(state, up);
        String downValue = valueName(state, down);
        return withValue(withValue(state, up, downValue), down, upValue);
    }

    private BlockState permuteConnections(BlockState state) {
        Map<Direction, Property<?>> named = new EnumMap<>(Direction.class);
        for (Property<?> property : state.getProperties()) {
            Direction direction = Direction.byName(property.getName());
            if (direction != null) {
                named.put(direction, property);
            }
        }
        if (named.isEmpty()) {
            return state;
        }
        Map<Direction, String> values = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, Property<?>> entry : named.entrySet()) {
            values.put(entry.getKey(), valueName(state, entry.getValue()));
        }
        Map<Direction, Direction> inverse = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, Direction> mapping : directions.entrySet()) {
            inverse.put(mapping.getValue(), mapping.getKey());
        }
        BlockState defaults = state.getBlock().defaultBlockState();
        BlockState out = state;
        for (Map.Entry<Direction, Property<?>> entry : named.entrySet()) {
            Direction source = inverse.get(entry.getKey());
            String value = source == null ? null : values.get(source);
            out = withValue(out, entry.getValue(), value != null ? value : valueName(defaults, entry.getValue()));
        }
        return out;
    }

    private BlockState stairs(BlockState state) {
        Direction facing = state.getValue(StairBlock.FACING);
        Direction halfSide = state.getValue(StairBlock.HALF) == Half.TOP ? Direction.UP : Direction.DOWN;
        Direction missingHorizontal = directions.get(facing.getOpposite());
        Direction missingVertical = directions.get(halfSide.getOpposite());
        Direction vertical;
        Direction horizontalSide;
        if (missingHorizontal.getAxis() != Direction.Axis.Y && missingVertical.getAxis() == Direction.Axis.Y) {
            horizontalSide = missingHorizontal;
            vertical = missingVertical;
        } else if (missingVertical.getAxis() != Direction.Axis.Y && missingHorizontal.getAxis() == Direction.Axis.Y) {
            horizontalSide = missingVertical;
            vertical = missingHorizontal;
        } else {
            return state;
        }
        return state.setValue(StairBlock.FACING, horizontalSide.getOpposite())
                .setValue(StairBlock.HALF, vertical == Direction.DOWN ? Half.TOP : Half.BOTTOM);
    }

    private BlockState transformProperty(BlockState state, Property<?> property) {
        Class<?> type = property.getValueClass();
        if (type == Direction.class) {
            Direction current = (Direction) state.getValue(property);
            return setDirection(state, property, directions.get(current));
        }
        if (type == Direction.Axis.class) {
            Direction.Axis axis = (Direction.Axis) state.getValue(property);
            Direction moved = directions.get(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
            return setAxis(state, property, moved.getAxis());
        }
        if (property == BlockStateProperties.ROTATION_16 && property instanceof IntegerProperty rot) {
            int value = state.getValue(rot);
            double angle = Math.toRadians(value * 22.5);
            Vec3 dir = transform.applyDirection(new Vec3(-Math.sin(angle), 0, Math.cos(angle)));
            if (Math.abs(dir.x()) < EPSILON && Math.abs(dir.z()) < EPSILON) {
                return state;
            }
            double degrees = Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
            return state.setValue(rot, Math.floorMod((int) Math.round(degrees / 22.5), 16));
        }
        return state;
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    private static <T extends Comparable<T>> BlockState setDirection(BlockState state, Property<T> property, Direction direction) {
        for (T value : property.getPossibleValues()) {
            if (value == direction) {
                return state.setValue(property, value);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setAxis(BlockState state, Property<T> property, Direction.Axis axis) {
        for (T value : property.getPossibleValues()) {
            if (value == axis) {
                return state.setValue(property, value);
            }
        }
        return state;
    }
}
