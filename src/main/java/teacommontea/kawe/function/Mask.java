package teacommontea.kawe.function;

@FunctionalInterface
public interface Mask {

    Mask ALWAYS = (x, y, z) -> true;
    Mask NEVER = (x, y, z) -> false;

    boolean test(int x, int y, int z);

    default Mask negate() {
        Mask self = this;
        return (x, y, z) -> !self.test(x, y, z);
    }

    default Mask and(Mask other) {
        Mask self = this;
        return (x, y, z) -> self.test(x, y, z) && other.test(x, y, z);
    }

    default Mask or(Mask other) {
        Mask self = this;
        return (x, y, z) -> self.test(x, y, z) || other.test(x, y, z);
    }
}
