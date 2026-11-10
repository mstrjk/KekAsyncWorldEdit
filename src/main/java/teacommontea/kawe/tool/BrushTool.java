package teacommontea.kawe.tool;

import org.bukkit.Location;
import teacommontea.kawe.Settings;
import teacommontea.kawe.command.CmdContext;
import teacommontea.kawe.command.CommandError;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.parse.Patterns;
import teacommontea.kawe.parse.Transforms;
import teacommontea.kawe.session.Actor;
import teacommontea.kawe.session.Navigation;

public class BrushTool implements Tool {

    public enum TargetMode {
        TARGET_BLOCK_RANGE, FORWARD_POINT_PITCH, TARGET_POINT_HEIGHT, TARGET_FACE_RANGE
    }

    public static final int DEFAULT_RANGE = 240;
    public static final int MAX_RANGE = 500;

    private BrushSettings right = new BrushSettings();
    private BrushSettings left = right;
    private int range = -1;
    private String traceMask;
    private TargetMode targetMode = TargetMode.TARGET_BLOCK_RANGE;
    private int targetOffset;

    @Override
    public String permission() {
        return null;
    }

    public synchronized BrushSettings right() {
        return right;
    }

    public synchronized BrushSettings left() {
        return left;
    }

    public synchronized void both(BrushSettings settings) {
        this.right = settings;
        this.left = settings;
    }

    public synchronized void right(BrushSettings settings) {
        this.right = settings;
    }

    public synchronized void left(BrushSettings settings) {
        this.left = settings;
    }

    public synchronized boolean isSet() {
        return right.brush() != null || left.brush() != null;
    }

    public synchronized boolean rangeSet() {
        return range >= 0;
    }

    public synchronized int range() {
        return range < 0 ? DEFAULT_RANGE : Math.min(range, MAX_RANGE);
    }

    public synchronized void range(int value) {
        this.range = value;
    }

    public synchronized String traceMask() {
        return traceMask;
    }

    public synchronized void traceMask(String value) {
        this.traceMask = value;
    }

    public synchronized TargetMode targetMode() {
        return targetMode;
    }

    public synchronized void targetMode(TargetMode value) {
        this.targetMode = value;
    }

    public synchronized int targetOffset() {
        return targetOffset;
    }

    public synchronized void targetOffset(int value) {
        this.targetOffset = value;
    }

    @Override
    public boolean rightClick(ToolClick click) {
        return act(click, right());
    }

    @Override
    public boolean leftClick(ToolClick click) {
        return act(click, left());
    }

    @Override
    public boolean scroll(ToolClick click, int amount) {
        BrushSettings settings = right();
        BrushSettings.Scroll action = settings.scroll();
        if (action == BrushSettings.Scroll.NONE) {
            return false;
        }
        click.run("scroll", c -> Scrolls.apply(c, this, settings, action, amount));
        return true;
    }

    private boolean act(ToolClick click, BrushSettings settings) {
        Brush brush = settings.brush();
        if (brush == null) {
            return false;
        }
        String permission = settings.permission();
        if (permission != null && !click.player().hasPermission(permission)) {
            click.run(settings.label(), c -> c.error("kawe.error.no-perm", permission));
            return true;
        }
        click.run(settings.label(), c -> {
            double size = settings.size();
            checkBrushRadius(size);
            Edit edit = c.edit();
            edit.captureTiles();
            BlockVec target = position(c, edit);
            if (target == null) {
                c.print("kawe.tool.no-block");
                return;
            }
            String maskInput = settings.mask();
            if (maskInput != null) {
                Mask brushMask = Masks.parse(maskInput, c.parseContext(edit));
                Mask existing = edit.mask();
                edit.mask(existing == null ? brushMask : existing.and(brushMask));
            }
            String sourceInput = settings.sourceMask();
            if (sourceInput != null) {
                Mask source = Masks.parse(sourceInput, c.parseContext(edit));
                Mask existing = edit.sourceMask();
                edit.sourceMask(existing == null ? source : existing.and(source));
            }
            String transformInput = settings.transform();
            if (transformInput != null) {
                edit.transform(Transforms.parse(transformInput, c.parseContext(edit)));
            }
            String material = settings.material();
            Pattern pattern = material == null ? null : Patterns.parse(material, c.parseContext(edit));
            BrushUse use = new BrushUse(c, edit, target, pattern, size);
            brush.build(use);
            Edit.Result result = c.commit(edit, !use.after().isEmpty());
            for (BrushUse.AfterCommit action : use.after()) {
                action.run(result);
            }
        });
        return true;
    }

    public static void checkBrushRadius(double size) throws CommandError {
        int max = Settings.get().maxBrushRadius();
        if (max >= 0 && size > max) {
            throw new CommandError("kawe.brush.radius-too-large", max);
        }
    }

    Mask stopMask(CmdContext c, Edit edit) throws Exception {
        String input = traceMask();
        return input == null ? (x, y, z) -> Masks.solid(edit.get(x, y, z)) : Masks.parse(input, c.parseContext(edit));
    }

    BlockVec trace(CmdContext c, Edit edit, boolean face) throws Exception {
        Actor actor = c.actor();
        boolean set = rangeSet();
        return Navigation.maskedTrace(edit, actor.eye(), actor.yaw(), actor.pitch(), set ? range() : MAX_RANGE, stopMask(c, edit), set, face);
    }

    BlockVec position(CmdContext c, Edit edit) throws Exception {
        Actor actor = c.actor();
        Mask stop = stopMask(c, edit);
        Location location = actor.location();
        Vec3 feet = new Vec3(location.getX(), location.getY(), location.getZ());
        Vec3 target;
        switch (targetMode()) {
            case FORWARD_POINT_PITCH -> {
                double angle = Math.toRadians(23 - actor.pitch() / 4);
                int distance = (int) (Math.sin(angle) * 50);
                double yaw = Math.toRadians(actor.yaw());
                double dx = -Math.sin(yaw);
                double dz = Math.cos(yaw);
                target = new Vec3(feet.x() + dx * distance, feet.y(), feet.z() + dz * distance);
            }
            case TARGET_POINT_HEIGHT -> {
                BlockVec at = actor.block();
                int y = at.y();
                while (y > edit.minY() && !Masks.blocksMovement(edit.get(at.x(), y, at.z()))) {
                    y--;
                }
                BlockVec hit = Navigation.maskedTrace(edit, actor.eye(), actor.yaw(), actor.pitch(), at.y() - y + 8, stop, true, false);
                target = hit == null ? null : hit.toVec3();
            }
            case TARGET_FACE_RANGE -> {
                BlockVec hit = Navigation.maskedTrace(edit, actor.eye(), actor.yaw(), actor.pitch(), range(), stop, true, true);
                target = hit == null ? null : hit.toVec3();
            }
            default -> {
                BlockVec hit = Navigation.maskedTrace(edit, actor.eye(), actor.yaw(), actor.pitch(), range(), stop, true, false);
                target = hit == null ? null : hit.toVec3();
            }
        }
        if (target == null) {
            return null;
        }
        int offset = targetOffset();
        if (offset != 0) {
            Vec3 away = target.subtract(feet);
            double length = Math.sqrt(away.x() * away.x() + away.y() * away.y() + away.z() * away.z());
            if (length > 0) {
                target = target.subtract(away.multiply(offset / length));
            }
        }
        return target.toBlock();
    }
}
