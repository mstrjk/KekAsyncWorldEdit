package teacommontea.kawe.region.selector;

import java.util.List;

public final class Selectors {

    public static final List<String> TYPES = List.of("cuboid", "extend", "poly", "ellipsoid", "sphere", "cyl", "convex", "fuzzy");

    private Selectors() {
    }

    public static String canonical(String type) {
        return switch (type) {
            case "hull", "polyhedron", "polyhedral" -> "convex";
            case "magic" -> "fuzzy";
            default -> TYPES.contains(type) ? type : null;
        };
    }

    public static Selector create(String type, int worldMinY, int worldMaxY) {
        String name = canonical(type);
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "extend" -> new ExtendingCuboidSelector();
            case "poly" -> new PolygonSelector(worldMinY, worldMaxY, -1);
            case "ellipsoid" -> new EllipsoidSelector();
            case "sphere" -> new SphereSelector();
            case "cyl" -> new CylinderSelector();
            case "convex" -> new ConvexSelector(-1);
            case "fuzzy" -> new FuzzySelector();
            default -> new CuboidSelector();
        };
    }
}
