package teacommontea.kawe.tool;

import java.util.List;

public final class BrushSettings {

    public enum Scroll {
        NONE, SIZE, RANGE, TARGET_OFFSET, TARGET_MODE, CLIPBOARD, MASK, PATTERN
    }

    private Brush brush;
    private String label = "";
    private String line = "";
    private String permission;
    private double size = 1;
    private String material;
    private String mask;
    private String sourceMask;
    private String transform;
    private Scroll scroll = Scroll.NONE;
    private List<String> scrollValues = List.of();
    private int scrollIndex;

    public synchronized BrushSettings copy() {
        BrushSettings out = new BrushSettings();
        out.brush = brush;
        out.label = label;
        out.line = line;
        out.permission = permission;
        out.size = size;
        out.material = material;
        out.mask = mask;
        out.sourceMask = sourceMask;
        out.transform = transform;
        out.scroll = scroll;
        out.scrollValues = scrollValues;
        out.scrollIndex = scrollIndex;
        return out;
    }

    public synchronized Brush brush() {
        return brush;
    }

    public synchronized void brush(Brush value, String newLabel, String newPermission) {
        this.brush = value;
        this.label = newLabel;
        this.permission = newPermission;
    }

    public synchronized String label() {
        return label;
    }

    public synchronized String line() {
        return line;
    }

    public synchronized void line(String value) {
        this.line = value;
    }

    public synchronized String permission() {
        return permission;
    }

    public synchronized double size() {
        return size;
    }

    public synchronized void size(double value) {
        this.size = value;
    }

    public synchronized String material() {
        return material;
    }

    public synchronized void material(String value) {
        this.material = value;
    }

    public synchronized String mask() {
        return mask;
    }

    public synchronized void mask(String value) {
        this.mask = value;
    }

    public synchronized String sourceMask() {
        return sourceMask;
    }

    public synchronized void sourceMask(String value) {
        this.sourceMask = value;
    }

    public synchronized String transform() {
        return transform;
    }

    public synchronized void transform(String value) {
        this.transform = value;
    }

    public synchronized Scroll scroll() {
        return scroll;
    }

    public synchronized List<String> scrollValues() {
        return scrollValues;
    }

    public synchronized void scroll(Scroll action, List<String> values) {
        this.scroll = action;
        this.scrollValues = List.copyOf(values);
        this.scrollIndex = 0;
    }

    public synchronized String nextScrollValue(int amount) {
        if (scrollValues.isEmpty()) {
            return null;
        }
        scrollIndex = Math.floorMod(scrollIndex + amount, scrollValues.size());
        return scrollValues.get(scrollIndex);
    }
}
