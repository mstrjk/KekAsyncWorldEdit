package teacommontea.kawe.region;

import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.math.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConvexPolyhedralRegion implements Region {

    public record Triangle(Vec3 a, Vec3 b, Vec3 c, Vec3 normal, double limit) {
        static Triangle of(Vec3 a, Vec3 b, Vec3 c) {
            Vec3 normal = b.subtract(a).cross(c.subtract(a)).normalize();
            return new Triangle(a, b, c, normal, Math.max(Math.max(normal.dot(a), normal.dot(b)), normal.dot(c)));
        }

        boolean above(Vec3 point) {
            return normal.dot(point) > limit;
        }

        Edge edge(int index) {
            return switch (index) {
                case 0 -> new Edge(a, b);
                case 1 -> new Edge(b, c);
                default -> new Edge(c, a);
            };
        }
    }

    private record Edge(Vec3 start, Vec3 end) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Edge edge && ((start.equals(edge.start) && end.equals(edge.end)) || (start.equals(edge.end) && end.equals(edge.start)));
        }

        @Override
        public int hashCode() {
            return start.hashCode() ^ end.hashCode();
        }
    }

    private final Set<BlockVec> vertices = new LinkedHashSet<>();
    private final Set<BlockVec> backlog = new LinkedHashSet<>();
    private final List<Triangle> triangles = new ArrayList<>();
    private BlockVec min;
    private BlockVec max;
    private long volume = -1;

    public ConvexPolyhedralRegion() {
    }

    public ConvexPolyhedralRegion(List<BlockVec> points) {
        for (BlockVec point : points) {
            addVertex(point);
        }
    }

    public boolean addVertex(BlockVec vertex) {
        if (vertices.contains(vertex)) {
            return false;
        }
        Vec3 point = vertex.toVec3();
        if (vertices.size() == 3) {
            if (backlog.contains(vertex)) {
                return false;
            }
            if (containsRaw(point)) {
                return backlog.add(vertex);
            }
        }
        vertices.add(vertex);
        volume = -1;
        min = min == null ? vertex : min.min(vertex);
        max = max == null ? vertex : max.max(vertex);
        if (vertices.size() < 3) {
            return true;
        }
        if (vertices.size() == 3) {
            BlockVec[] v = vertices.toArray(BlockVec[]::new);
            triangles.add(Triangle.of(v[0].toVec3(), v[1].toVec3(), v[2].toVec3()));
            triangles.add(Triangle.of(v[0].toVec3(), v[2].toVec3(), v[1].toVec3()));
            return true;
        }
        Set<Edge> border = new LinkedHashSet<>();
        for (Iterator<Triangle> it = triangles.iterator(); it.hasNext(); ) {
            Triangle triangle = it.next();
            if (!triangle.above(point)) {
                continue;
            }
            it.remove();
            for (int i = 0; i < 3; i++) {
                Edge edge = triangle.edge(i);
                if (!border.remove(edge)) {
                    border.add(edge);
                }
            }
        }
        for (Edge edge : border) {
            triangles.add(Triangle.of(edge.start(), edge.end(), point));
        }
        if (!backlog.isEmpty()) {
            vertices.remove(vertex);
            List<BlockVec> pending = new ArrayList<>(backlog);
            backlog.clear();
            for (BlockVec next : pending) {
                addVertex(next);
            }
            vertices.add(vertex);
        }
        return true;
    }

    public boolean isDefined() {
        return !triangles.isEmpty();
    }

    public List<BlockVec> vertices() {
        List<BlockVec> out = new ArrayList<>(vertices);
        out.addAll(backlog);
        return out;
    }

    public List<Triangle> triangles() {
        return List.copyOf(triangles);
    }

    private boolean containsRaw(Vec3 point) {
        for (Triangle triangle : triangles) {
            if (triangle.above(point)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public BlockVec min() {
        return min == null ? BlockVec.ZERO : min;
    }

    @Override
    public BlockVec max() {
        return max == null ? BlockVec.ZERO : max;
    }

    @Override
    public Vec3 center() {
        if (vertices.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 sum = Vec3.ZERO;
        for (BlockVec vertex : vertices) {
            sum = sum.add(vertex.toVec3());
        }
        return sum.divide(vertices.size());
    }

    @Override
    public synchronized long volume() {
        if (volume < 0) {
            long count = 0;
            BlockVec lo = min();
            BlockVec hi = max();
            for (int x = lo.x(); x <= hi.x(); x++) {
                for (int y = lo.y(); y <= hi.y(); y++) {
                    for (int z = lo.z(); z <= hi.z(); z++) {
                        if (contains(x, y, z)) {
                            count++;
                        }
                    }
                }
            }
            volume = count;
        }
        return volume;
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (!isDefined() || x < min.x() || y < min.y() || z < min.z() || x > max.x() || y > max.y() || z > max.z()) {
            return false;
        }
        return containsRaw(new Vec3(x, y, z));
    }

    @Override
    public void expand(BlockVec... changes) {
    }

    @Override
    public void contract(BlockVec... changes) {
    }

    @Override
    public void shift(BlockVec change) {
        List<BlockVec> moved = new ArrayList<>();
        for (BlockVec vertex : vertices()) {
            moved.add(vertex.add(change));
        }
        vertices.clear();
        backlog.clear();
        triangles.clear();
        min = null;
        max = null;
        volume = -1;
        for (BlockVec vertex : moved) {
            addVertex(vertex);
        }
    }

    @Override
    public ConvexPolyhedralRegion copy() {
        return new ConvexPolyhedralRegion(vertices());
    }
}
