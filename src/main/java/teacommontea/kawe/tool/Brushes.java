package teacommontea.kawe.tool;

import net.kyori.adventure.text.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.command.CmdContext;
import teacommontea.kawe.command.CommandError;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Mask;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Spline;
import teacommontea.kawe.math.Vec3;
import teacommontea.kawe.op.ClipboardOps;
import teacommontea.kawe.op.RegionOps;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.parse.Patterns;
import teacommontea.kawe.region.CuboidRegion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class Brushes {

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final int[][] DIAGONALS = diagonals();

    private Brushes() {
    }

    private static int[][] diagonals() {
        List<int[]> out = new ArrayList<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        out.add(new int[]{x, y, z});
                    }
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1] + a[2] * a[2], b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        return out.toArray(int[][]::new);
    }

    static boolean passable(BlockState state) {
        return state.isAir() || !Masks.blocksMovement(state);
    }

    static boolean inWorld(Edit edit, int y) {
        return y >= edit.minY() && y <= edit.maxY();
    }

    static int[] openFace(Edit edit, int x, int y, int z) {
        for (int[] face : FACES) {
            int ny = y + face[1];
            if (inWorld(edit, ny) && passable(edit.get(x + face[0], ny, z + face[2]))) {
                return face;
            }
        }
        return null;
    }

    static boolean surface(Edit edit, int x, int y, int z) {
        return inWorld(edit, y) && !passable(edit.get(x, y, z)) && openFace(edit, x, y, z) != null;
    }

    static long distanceSq(BlockVec a, int x, int y, int z) {
        long dx = x - a.x();
        long dy = y - a.y();
        long dz = z - a.z();
        return dx * dx + dy * dy + dz * dz;
    }

    static List<BlockVec> surfaceArea(Edit edit, BlockVec origin, double size) {
        long limit = (long) Math.floor(size) * (long) Math.floor(size);
        List<BlockVec> out = new ArrayList<>();
        Set<BlockVec> seen = new HashSet<>();
        ArrayDeque<BlockVec> queue = new ArrayDeque<>();
        queue.add(origin);
        seen.add(origin);
        while (!queue.isEmpty()) {
            BlockVec at = queue.poll();
            if (!surface(edit, at.x(), at.y(), at.z())) {
                continue;
            }
            out.add(at);
            for (int[] d : DIAGONALS) {
                BlockVec next = at.add(d[0], d[1], d[2]);
                if (distanceSq(origin, next.x(), next.y(), next.z()) <= limit && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return out;
    }

    public static final class Line implements Brush {
        private final boolean shell;
        private final boolean select;
        private final boolean flat;
        private BlockVec first;

        public Line(boolean shell, boolean select, boolean flat) {
            this.shell = shell;
            this.select = select;
            this.flat = flat;
        }

        @Override
        public synchronized void build(BrushUse u) {
            if (first == null) {
                first = u.target();
                u.context().print("kawe.brush.brush.line.primary", u.target().toString());
                return;
            }
            RegionOps.line(u.edit(), first, u.target(), u.pattern(), u.size(), !shell, flat);
            u.context().print("kawe.brush.brush.line.secondary");
            first = select ? u.target() : null;
        }
    }

    public static final class SplinePoints implements Brush {
        private static final int MAX_POINTS = 15;
        private final List<List<BlockVec>> sets = new ArrayList<>();
        private int splines;
        private BlockVec last;

        @Override
        public synchronized void build(BrushUse u) throws CommandError {
            Edit edit = u.edit();
            BlockVec target = u.target();
            if (!target.equals(last)) {
                last = target;
                if (sets.size() >= MAX_POINTS) {
                    throw new CommandError(Component.text("A spline takes at most " + MAX_POINTS + " points"));
                }
                List<BlockVec> points = new ArrayList<>();
                if (u.size() > 0) {
                    BlockState type = edit.get(target.x(), target.y(), target.z());
                    Mask mask = edit.mask();
                    Set<BlockVec> seen = new HashSet<>();
                    ArrayDeque<BlockVec> queue = new ArrayDeque<>();
                    queue.add(target);
                    seen.add(target);
                    long limit = (long) Math.floor(u.size()) * (long) Math.floor(u.size());
                    while (!queue.isEmpty()) {
                        BlockVec at = queue.poll();
                        if (edit.get(at.x(), at.y(), at.z()) != type || (mask != null && !mask.test(at.x(), at.y(), at.z()))) {
                            continue;
                        }
                        points.add(at);
                        for (int[] d : DIAGONALS) {
                            BlockVec next = at.add(d[0], d[1], d[2]);
                            if (distanceSq(target, next.x(), next.y(), next.z()) <= limit && seen.add(next)) {
                                queue.add(next);
                            }
                        }
                    }
                    splines = Math.max(splines, points.size());
                } else {
                    points.add(target);
                    splines = Math.max(splines, 1);
                }
                sets.add(points);
                u.context().print("kawe.brush.spline.primary.2");
                return;
            }
            if (sets.size() < 2) {
                u.context().print("kawe.brush.brush.spline.secondary.error");
                return;
            }
            for (int i = 0; i < splines; i++) {
                List<BlockVec> nodes = new ArrayList<>();
                for (List<BlockVec> points : sets) {
                    if (!points.isEmpty()) {
                        nodes.add(points.get((int) ((long) i * points.size() / splines)));
                    }
                }
                RegionOps.spline(edit, nodes, u.pattern(), 0, 0, 0, 10, 0, true);
            }
            u.context().print("kawe.brush.spline.secondary");
            sets.clear();
            splines = 0;
            last = null;
        }
    }

    public static final class SurfaceSpline implements Brush {
        private final double tension;
        private final double bias;
        private final double continuity;
        private final double quality;
        private final List<BlockVec> path = new ArrayList<>();

        public SurfaceSpline(double tension, double bias, double continuity, double quality) {
            this.tension = tension;
            this.bias = bias;
            this.continuity = continuity;
            this.quality = quality;
        }

        @Override
        public synchronized void build(BrushUse u) {
            Edit edit = u.edit();
            BlockVec target = u.target();
            if (path.isEmpty() || !target.equals(path.getLast())) {
                int y = Masks.nearestSurface(edit, target.x(), target.z(), target.y());
                if (y == Integer.MIN_VALUE) {
                    return;
                }
                path.add(new BlockVec(target.x(), y, target.z()));
                u.context().print("kawe.brush.spline.primary.2");
                return;
            }
            List<Vec3> nodes = new ArrayList<>();
            for (BlockVec point : path) {
                nodes.add(point.toVec3());
            }
            Spline spline = new Spline(nodes, tension, bias, continuity);
            double length = spline.length();
            double step = length <= 0 ? 1 : Math.max(1.0E-6, 1 / length / quality);
            Set<BlockVec> trail = new LinkedHashSet<>();
            for (double t = 0; t <= 1; t += step) {
                Vec3 tip = spline.position(t);
                int x = (int) Math.round(tip.x());
                int z = (int) Math.round(tip.z());
                int y = Masks.nearestSurface(edit, x, z, (int) Math.round(tip.y()));
                if (y != Integer.MIN_VALUE) {
                    trail.add(new BlockVec(x, y, z));
                }
            }
            double radius = u.size();
            Set<BlockVec> place = new LinkedHashSet<>();
            if (radius == 0) {
                place.addAll(trail);
            } else {
                int reach = (int) Math.ceil(radius);
                double limit = radius * radius;
                for (BlockVec tip : trail) {
                    for (int x = tip.x() - reach; x <= tip.x() + reach; x++) {
                        for (int z = tip.z() - reach; z <= tip.z() + reach; z++) {
                            double dx = x - tip.x();
                            double dz = z - tip.z();
                            if (dx * dx + dz * dz <= limit) {
                                int y = Masks.nearestSurface(edit, x, z, tip.y());
                                if (y != Integer.MIN_VALUE) {
                                    place.add(new BlockVec(x, y, z));
                                }
                            }
                        }
                    }
                }
            }
            for (BlockVec at : place) {
                u.pattern().apply(edit, at.x(), at.y(), at.z());
            }
            path.clear();
            u.context().print("kawe.brush.spline.secondary");
        }
    }

    public static final class Catenary implements Brush {
        private final boolean shell;
        private final boolean select;
        private final boolean facing;
        private final double slack;
        private BlockVec first;
        private BlockVec vertex;

        public Catenary(boolean shell, boolean select, boolean facing, double slack) {
            this.shell = shell;
            this.select = select;
            this.facing = facing;
            this.slack = slack;
        }

        @Override
        public synchronized void build(BrushUse u) {
            BlockVec second = u.target();
            CmdContext c = u.context();
            if (first == null || second.equals(first)) {
                first = second;
                c.print("kawe.brush.brush.line.primary", second.toString());
                return;
            }
            if (vertex == null) {
                vertex = vertex(first.toVec3(), second.toVec3(), slack);
                if (facing) {
                    c.print("kawe.brush.brush.catenary.direction", 2);
                    return;
                }
            } else if (facing) {
                double yaw = Math.toRadians(c.actor().yaw());
                double pitch = Math.toRadians(c.actor().pitch());
                Vec3 look = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
                Vec3 middle = first.toVec3().add(second.toVec3()).multiply(0.5);
                Vec3 offset = middle.subtract(vertex.toVec3());
                double length = Math.sqrt(offset.x() * offset.x() + offset.y() * offset.y() + offset.z() * offset.z());
                vertex = middle.add(look.multiply(length)).toBlock();
            }
            RegionOps.spline(u.edit(), List.of(first, vertex, second), u.pattern(), 0, 0, 0, 10, u.size(), !shell);
            vertex = null;
            c.print("kawe.brush.brush.line.secondary");
            first = select ? second : null;
        }

        static BlockVec vertex(Vec3 a, Vec3 b, double slack) {
            Vec3 middle = a.add(b).multiply(0.5);
            if (slack <= 1) {
                return middle.toBlock();
            }
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            double straight = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double length = straight * slack;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal < 1.0E-9) {
                return middle.toBlock();
            }
            double target = Math.sqrt(length * length - dy * dy) / 2;
            double low = 1.0E-6;
            double high = Math.max(1, horizontal * 1000);
            for (int i = 0; i < 200; i++) {
                double mid = (low + high) / 2;
                if (mid * Math.sinh(horizontal / (2 * mid)) > target) {
                    low = mid;
                } else {
                    high = mid;
                }
            }
            double scale = (low + high) / 2;
            double vertX = (horizontal - scale * Math.log((length + dy) / (length - dy))) / 2.0;
            double z = (horizontal / 2) / scale;
            double vertY = scale + (dy - length * (Math.cosh(z) / Math.sinh(z))) / 2;
            double t = vertX / horizontal;
            return new Vec3(a.x() + dx * t, a.y() + dy * t + vertY, a.z() + dz * t).toBlock();
        }
    }

    public static final class Sweep implements Brush {
        private final List<BlockVec> positions = new ArrayList<>();
        private final int copies;

        public Sweep(int copies) {
            this.copies = copies > 0 ? copies : -1;
        }

        @Override
        public synchronized void build(BrushUse u) throws Exception {
            BlockVec target = u.target();
            CmdContext c = u.context();
            if (positions.isEmpty() || !target.equals(positions.getLast())) {
                positions.add(target);
                c.print("kawe.brush.spline.primary.2");
                return;
            }
            if (positions.size() < 2) {
                c.print("kawe.brush.brush.spline.secondary.error");
                return;
            }
            ClipboardHolder holder = c.clipboard();
            Clipboard clipboard = holder.clipboard();
            BlockVec dimensions = clipboard.dimensions();
            List<Vec3> nodes = new ArrayList<>();
            for (BlockVec point : positions) {
                nodes.add(point.toVec3());
            }
            Spline spline = new Spline(nodes, 0, 0, 0);
            double base = dimensions.x() > dimensions.z() ? Math.PI / 2 : 0;
            List<Double> stops = new ArrayList<>();
            if (copies == 1) {
                stops.add(0.0);
            } else if (copies == -1) {
                double length = spline.length();
                double step = length <= 0 ? 1 : Math.max(1.0E-6, 1 / (length * Math.max(dimensions.x(), dimensions.z())));
                for (double t = 0; t <= 1; t += step) {
                    stops.add(t);
                }
            } else {
                for (int i = 0; i < copies; i++) {
                    stops.add(i / (double) (copies - 1));
                }
            }
            Vec3 centerOffset = clipboard.region().center().subtract(clipboard.origin().toVec3());
            for (double t : stops) {
                Vec3 at = spline.position(t);
                Vec3 ahead = spline.position(Math.min(1, t + 1.0E-3));
                Vec3 behind = spline.position(Math.max(0, t - 1.0E-3));
                double angle = Math.toDegrees(Math.atan2(ahead.x() - behind.x(), ahead.z() - behind.z()) - base);
                Transform transform = Transform.IDENTITY.rotateY(-angle).combine(holder.transform());
                BlockVec to = at.toBlock().subtract(transform.apply(centerOffset).toBlock());
                ClipboardOps.paste(u.edit(), transform, clipboard, to, false, false, null);
            }
            c.print("kawe.brush.spline.secondary");
            positions.clear();
        }
    }

    public static Brush circle(boolean filled) {
        return u -> {
            BlockVec center = u.target();
            Vec3 normal = center.toVec3().subtract(u.context().actor().eye());
            double length = Math.sqrt(normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z());
            if (length == 0) {
                return;
            }
            Vec3 n = normal.multiply(1 / length);
            double radius = u.size();
            int reach = (int) Math.ceil(radius);
            double outer = (radius + 0.5) * (radius + 0.5);
            double inner = (radius - 0.5) * (radius - 0.5);
            for (int x = -reach; x <= reach; x++) {
                for (int y = -reach; y <= reach; y++) {
                    for (int z = -reach; z <= reach; z++) {
                        double along = x * n.x() + y * n.y() + z * n.z();
                        if (Math.abs(along) > 0.5) {
                            continue;
                        }
                        double inPlane = x * x + y * y + z * z - along * along;
                        if (inPlane <= outer && (filled || inPlane >= inner)) {
                            u.pattern().apply(u.edit(), center.x() + x, center.y() + y, center.z() + z);
                        }
                    }
                }
            }
        };
    }

    public static Brush recurse(boolean depthFirst) {
        return u -> {
            Edit edit = u.edit();
            BlockVec origin = u.target();
            if (edit.get(origin.x(), origin.y(), origin.z()).isAir()) {
                return;
            }
            Mask mask = edit.mask();
            edit.mask(null);
            int depthLimit = (int) u.size();
            long radiusSq = (long) depthLimit * depthLimit;
            Set<BlockVec> seen = new HashSet<>();
            ArrayDeque<BlockVec> queue = new ArrayDeque<>();
            Map<BlockVec, Integer> depth = new HashMap<>();
            queue.add(origin);
            seen.add(origin);
            depth.put(origin, 0);
            List<BlockVec> hits = new ArrayList<>();
            while (!queue.isEmpty()) {
                BlockVec at = depthFirst ? queue.pollLast() : queue.pollFirst();
                if (mask != null && !mask.test(at.x(), at.y(), at.z())) {
                    continue;
                }
                hits.add(at);
                int level = depth.get(at);
                for (int[] face : FACES) {
                    BlockVec next = at.add(face[0], face[1], face[2]);
                    if (next.y() >= edit.maxY() || next.y() < edit.minY() || seen.contains(next)) {
                        continue;
                    }
                    boolean inside = depthFirst ? distanceSq(origin, next.x(), next.y(), next.z()) <= radiusSq : level + 1 <= depthLimit;
                    if (inside) {
                        seen.add(next);
                        depth.put(next, level + 1);
                        queue.add(next);
                    }
                }
            }
            for (BlockVec at : hits) {
                u.pattern().apply(edit, at.x(), at.y(), at.z());
            }
        };
    }

    public static List<BlockVec> scatterPoints(Edit edit, BlockVec origin, double size, int count, int distance) {
        List<BlockVec> area = size == 0 ? List.of(origin) : surfaceArea(edit, origin, size);
        List<BlockVec> placed = new ArrayList<>();
        if (area.isEmpty()) {
            return placed;
        }
        Mask mask = edit.mask();
        int spacing = Math.min((int) size, distance);
        long spacingSq = (long) spacing * spacing;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int fails = 1000;
        for (int i = 0; i < count; i++) {
            BlockVec at = area.get(random.nextInt(area.size()));
            if (mask != null && !mask.test(at.x(), at.y(), at.z())) {
                continue;
            }
            boolean close = false;
            for (BlockVec other : placed) {
                if (distanceSq(other, at.x(), at.y(), at.z()) <= spacingSq) {
                    close = true;
                    break;
                }
            }
            if (close && spacing > 0) {
                if (fails-- <= 0) {
                    break;
                }
                i--;
                continue;
            }
            placed.add(at);
        }
        return placed;
    }

    public static Brush scatter(int count, int distance, boolean overlay) {
        return u -> {
            Edit edit = u.edit();
            for (BlockVec at : scatterPoints(edit, u.target(), u.size(), count, distance)) {
                if (!overlay) {
                    u.pattern().apply(edit, at.x(), at.y(), at.z());
                    continue;
                }
                int[] face = openFace(edit, at.x(), at.y(), at.z());
                if (face != null) {
                    u.pattern().apply(edit, at.x() + face[0], at.y() + face[1], at.z() + face[2]);
                }
            }
        };
    }

    public static Brush splatter(int count, int recursion, boolean solid) {
        return u -> {
            Edit edit = u.edit();
            long limit = (long) (u.size() * u.size());
            Set<BlockVec> placed = new HashSet<>();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (BlockVec start : scatterPoints(edit, u.target(), u.size(), count, 1)) {
                Pattern pattern = u.pattern();
                if (solid) {
                    BlockState fixed = pattern.state(start.x(), start.y(), start.z());
                    CompoundTag tile = pattern.tile(start.x(), start.y(), start.z());
                    pattern = new Pattern() {
                        @Override
                        public BlockState state(int x, int y, int z) {
                            return fixed;
                        }

                        @Override
                        public CompoundTag tile(int x, int y, int z) {
                            return tile;
                        }
                    };
                }
                List<BlockVec> frontier = List.of(start);
                placed.add(start);
                pattern.apply(edit, start.x(), start.y(), start.z());
                for (int level = 0; level < recursion && !frontier.isEmpty(); level++) {
                    List<BlockVec> next = new ArrayList<>();
                    for (BlockVec at : frontier) {
                        int branches = 0;
                        for (int[] d : DIAGONALS) {
                            if (branches >= 2) {
                                break;
                            }
                            BlockVec to = at.add(d[0], d[1], d[2]);
                            if (distanceSq(u.target(), to.x(), to.y(), to.z()) < limit && !placed.contains(to)
                                    && random.nextInt(5) < 2 && surface(edit, to.x(), to.y(), to.z())) {
                                placed.add(to);
                                pattern.apply(edit, to.x(), to.y(), to.z());
                                next.add(to);
                                branches++;
                            }
                        }
                    }
                    frontier = next;
                }
            }
        };
    }

    public static Brush shatter(int count) {
        return u -> {
            Edit edit = u.edit();
            List<BlockVec> seeds = scatterPoints(edit, u.target(), u.size(), count, 1);
            long limit = (long) (u.size() * u.size());
            Map<BlockVec, Integer> owner = new HashMap<>();
            List<List<BlockVec>> frontiers = new ArrayList<>();
            for (int i = 0; i < seeds.size(); i++) {
                owner.put(seeds.get(i), i);
                frontiers.add(new ArrayList<>(List.of(seeds.get(i))));
            }
            Mask mask = edit.mask();
            ThreadLocalRandom random = ThreadLocalRandom.current();
            Set<BlockVec> borders = new HashSet<>();
            boolean growing = true;
            while (growing) {
                growing = false;
                for (int i = 0; i < frontiers.size(); i++) {
                    List<BlockVec> frontier = frontiers.get(i);
                    if (frontier.isEmpty()) {
                        continue;
                    }
                    growing = true;
                    List<BlockVec> next = new ArrayList<>();
                    for (BlockVec at : frontier) {
                        if (random.nextBoolean()) {
                            next.add(at);
                            continue;
                        }
                        for (int[] d : DIAGONALS) {
                            BlockVec to = at.add(d[0], d[1], d[2]);
                            if (distanceSq(u.target(), to.x(), to.y(), to.z()) > limit || !surface(edit, to.x(), to.y(), to.z())
                                    || (mask != null && !mask.test(to.x(), to.y(), to.z()))) {
                                continue;
                            }
                            Integer claimed = owner.get(to);
                            if (claimed == null) {
                                owner.put(to, i);
                                next.add(to);
                            } else if (claimed != i) {
                                borders.add(to);
                            }
                        }
                    }
                    frontiers.set(i, next);
                }
            }
            for (BlockVec at : borders) {
                u.pattern().apply(edit, at.x(), at.y(), at.z());
            }
        };
    }

    public static Brush layers(List<String> inputs) {
        return u -> {
            Edit edit = u.edit();
            List<Pattern> layers = new ArrayList<>();
            for (String input : inputs) {
                layers.add(Patterns.parse(input, u.context().parseContext(edit)));
            }
            List<BlockVec> current = new ArrayList<>();
            for (BlockVec at : surfaceArea(edit, u.target(), u.size())) {
                if (Masks.solid(edit.get(at.x(), at.y(), at.z()))) {
                    current.add(at);
                }
            }
            Set<BlockVec> done = new HashSet<>(current);
            for (int depth = 0; depth < layers.size() && !current.isEmpty(); depth++) {
                Pattern layer = layers.get(depth);
                List<BlockVec> next = new ArrayList<>();
                for (BlockVec at : current) {
                    for (int[] face : FACES) {
                        BlockVec to = at.add(face[0], face[1], face[2]);
                        if (inWorld(edit, to.y()) && !done.contains(to) && Masks.solid(edit.get(to.x(), to.y(), to.z()))
                                && openFace(edit, to.x(), to.y(), to.z()) == null) {
                            done.add(to);
                            next.add(to);
                        }
                    }
                }
                for (BlockVec at : current) {
                    layer.apply(edit, at.x(), at.y(), at.z());
                }
                current = next;
            }
        };
    }

    public static Brush surfaceSphere() {
        return u -> {
            for (BlockVec at : surfaceArea(u.edit(), u.target(), u.size())) {
                u.pattern().apply(u.edit(), at.x(), at.y(), at.z());
            }
        };
    }

    public static Brush populate(List<Clipboard> clipboards, String maskInput, int rarity, boolean rotate) {
        return u -> {
            Edit edit = u.edit();
            Mask mask = maskInput == null ? null : Masks.parse(maskInput, u.context().parseContext(edit));
            int size = (int) Math.round(u.size());
            BlockVec at = u.target();
            CuboidRegion area = new CuboidRegion(at.subtract(size, size, size), at.add(size, size, size));
            ThreadLocalRandom random = ThreadLocalRandom.current();
            int bottom = Math.max(edit.minY(), area.min().y());
            int top = Math.min(edit.maxY(), area.max().y());
            Mask ground = (x, y, z) -> Masks.blocksMovement(edit.get(x, y, z));
            for (long key : area.chunks()) {
                if (random.nextInt(100) >= rarity) {
                    continue;
                }
                int x = (ChunkKey.x(key) << 4) + random.nextInt(16);
                int z = (ChunkKey.z(key) << 4) + random.nextInt(16);
                if (x < area.min().x() || x > area.max().x() || z < area.min().z() || z > area.max().z()) {
                    continue;
                }
                int y = RegionOps.highest(edit, x, z, bottom, top, ground);
                if (y < bottom || !ground.test(x, y, z) || (mask != null && !mask.test(x, y, z))) {
                    continue;
                }
                Clipboard clipboard = clipboards.get(random.nextInt(clipboards.size()));
                Transform transform = rotate ? Transform.IDENTITY.rotateY(random.nextInt(4) * 90) : Transform.IDENTITY;
                ClipboardOps.paste(edit, transform, clipboard, new BlockVec(x, y + 1, z), false, false, null);
            }
        };
    }

    public static final class CopyPaste implements Brush {
        private final boolean randomRotate;
        private final boolean autoRotate;

        public CopyPaste(boolean randomRotate, boolean autoRotate) {
            this.randomRotate = randomRotate;
            this.autoRotate = autoRotate;
        }

        @Override
        public void build(BrushUse u) throws Exception {
            CmdContext c = u.context();
            Edit edit = u.edit();
            ClipboardHolder holder = c.session().clipboard();
            BlockVec target = u.target();
            if (holder == null) {
                Mask mask = edit.mask();
                int limit = (int) u.size();
                Set<BlockVec> seen = new HashSet<>();
                ArrayDeque<BlockVec> queue = new ArrayDeque<>();
                Map<BlockVec, Integer> depth = new HashMap<>();
                List<BlockVec> blocks = new ArrayList<>();
                queue.add(target);
                seen.add(target);
                depth.put(target, 0);
                while (!queue.isEmpty()) {
                    BlockVec at = queue.poll();
                    if (at.y() < target.y() || !inWorld(edit, at.y()) || edit.get(at.x(), at.y(), at.z()).isAir()
                            || (mask != null && !mask.test(at.x(), at.y(), at.z()))) {
                        continue;
                    }
                    blocks.add(at);
                    int level = depth.get(at);
                    if (level >= limit) {
                        continue;
                    }
                    for (int[] face : FACES) {
                        BlockVec next = at.add(face[0], face[1], face[2]);
                        if (seen.add(next)) {
                            depth.put(next, level + 1);
                            queue.add(next);
                        }
                    }
                }
                if (blocks.isEmpty()) {
                    return;
                }
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;
                int maxZ = Integer.MIN_VALUE;
                for (BlockVec at : blocks) {
                    minX = Math.min(minX, at.x());
                    minY = Math.min(minY, at.y());
                    minZ = Math.min(minZ, at.z());
                    maxX = Math.max(maxX, at.x());
                    maxY = Math.max(maxY, at.y());
                    maxZ = Math.max(maxZ, at.z());
                }
                Clipboard clipboard = new Clipboard(new CuboidRegion(new BlockVec(minX, minY, minZ), new BlockVec(maxX, maxY, maxZ)), target);
                for (BlockVec at : blocks) {
                    BlockState state = edit.get(at.x(), at.y(), at.z());
                    clipboard.set(at.x(), at.y(), at.z(), state, state.hasBlockEntity() ? edit.tile(at.x(), at.y(), at.z()) : null);
                }
                c.session().clipboard(new ClipboardHolder(clipboard));
                c.print("kawe.copy.command.copy", blocks.size());
                return;
            }
            Transform transform = holder.transform();
            if (randomRotate) {
                transform = Transform.IDENTITY.rotateY(ThreadLocalRandom.current().nextInt(4) * 90).combine(transform);
            }
            if (autoRotate) {
                int quarter = Math.floorMod(Math.round(c.actor().yaw() / 90f), 4);
                transform = Transform.IDENTITY.rotateY(-quarter * 90).combine(transform);
            }
            ClipboardOps.paste(edit, transform, holder.clipboard(), target.add(0, 1, 0), false, false, null);
        }
    }
}
