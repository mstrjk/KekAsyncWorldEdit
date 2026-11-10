package teacommontea.kawe.expr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleUnaryOperator;

final class Functions {

    @FunctionalInterface
    interface Function {
        double apply(Frame frame, Node[] args, int position);
    }

    record Signature(int arity, boolean varargs, boolean variableArgs, Function function) {
    }

    private static final Map<String, List<Signature>> REGISTRY = new HashMap<>();
    private static final Map<Integer, double[]> GLOBAL_BUFFER = new ConcurrentHashMap<>();

    static {
        unary("sin", Math::sin);
        unary("cos", Math::cos);
        unary("tan", Math::tan);
        unary("asin", Math::asin);
        unary("acos", Math::acos);
        unary("atan", Math::atan);
        unary("sinh", Math::sinh);
        unary("cosh", Math::cosh);
        unary("tanh", Math::tanh);
        unary("sqrt", Math::sqrt);
        unary("cbrt", Math::cbrt);
        unary("abs", Math::abs);
        unary("ceil", Math::ceil);
        unary("floor", Math::floor);
        unary("rint", Math::rint);
        unary("exp", Math::exp);
        unary("log", Math::log);
        unary("ln", Math::log);
        unary("log10", Math::log10);
        unary("round", v -> (double) Math.round(v));
        add("atan2", 2, false, false, (f, a, p) -> Math.atan2(a[0].eval(f), a[1].eval(f)));
        add("min", 1, true, false, (f, a, p) -> {
            double min = a[0].eval(f);
            for (int i = 1; i < a.length; i++) {
                min = Math.min(min, a[i].eval(f));
            }
            return min;
        });
        add("max", 1, true, false, (f, a, p) -> {
            double max = a[0].eval(f);
            for (int i = 1; i < a.length; i++) {
                max = Math.max(max, a[i].eval(f));
            }
            return max;
        });
        add("rotate", 3, false, true, Functions::rotate);
        add("swap", 2, false, true, Functions::swap);
        add("random", 0, false, false, (f, a, p) -> ThreadLocalRandom.current().nextDouble());
        add("randint", 1, false, false, (f, a, p) -> {
            double bound = Math.floor(a[0].eval(f));
            if (!(bound >= 1 && bound <= Integer.MAX_VALUE)) {
                throw new ExpressionException(p, "randint bound must be between 1 and " + Integer.MAX_VALUE);
            }
            return ThreadLocalRandom.current().nextInt((int) bound);
        });
        add("perlin", 7, false, false, (f, a, p) -> noise(p, () -> Noise.perlin((int) a[0].eval(f), a[1].eval(f), a[2].eval(f), a[3].eval(f),
                a[4].eval(f), (int) a[5].eval(f), a[6].eval(f)), "Perlin noise error: "));
        add("voronoi", 5, false, false, (f, a, p) -> noise(p, () -> Noise.voronoi((int) a[0].eval(f), a[1].eval(f), a[2].eval(f), a[3].eval(f),
                a[4].eval(f)), "Voronoi error: "));
        add("ridgedmulti", 6, false, false, (f, a, p) -> noise(p, () -> Noise.ridged((int) a[0].eval(f), a[1].eval(f), a[2].eval(f), a[3].eval(f),
                a[4].eval(f), (int) a[5].eval(f)), "Ridged multi error: "));
        add("megabuf", 1, false, false, (f, a, p) -> get(f.owner.buffer(), (int) a[0].eval(f), p));
        add("megabuf", 2, false, false, (f, a, p) -> set(f.owner.buffer(), (int) a[0].eval(f), a[1].eval(f), p));
        add("gmegabuf", 1, false, false, (f, a, p) -> get(GLOBAL_BUFFER, (int) a[0].eval(f), p));
        add("gmegabuf", 2, false, false, (f, a, p) -> set(GLOBAL_BUFFER, (int) a[0].eval(f), a[1].eval(f), p));
        add("closest", 6, false, false, (f, a, p) -> closest(f.owner.buffer(), f, a, p));
        add("gclosest", 6, false, false, (f, a, p) -> closest(GLOBAL_BUFFER, f, a, p));
        add("query", 5, false, true, (f, a, p) -> query(f, a, p, 0));
        add("queryAbs", 5, false, true, (f, a, p) -> query(f, a, p, 1));
        add("queryRel", 5, false, true, (f, a, p) -> query(f, a, p, 2));
    }

    private Functions() {
    }

    private static void unary(String name, DoubleUnaryOperator op) {
        add(name, 1, false, false, (f, a, p) -> op.applyAsDouble(a[0].eval(f)));
    }

    private static void add(String name, int arity, boolean varargs, boolean variableArgs, Function function) {
        REGISTRY.computeIfAbsent(name, k -> new ArrayList<>()).add(new Signature(arity, varargs, variableArgs, function));
    }

    static boolean exists(String name) {
        return REGISTRY.containsKey(name);
    }

    static Signature resolve(String name, int count, int position) {
        List<Signature> options = REGISTRY.get(name);
        if (options == null) {
            throw new ExpressionException(position, "Unknown function '" + name + "'");
        }
        for (Signature signature : options) {
            if (signature.varargs() ? count >= signature.arity() : count == signature.arity()) {
                return signature;
            }
        }
        StringBuilder counts = new StringBuilder();
        for (Signature signature : options) {
            if (!counts.isEmpty()) {
                counts.append(", ");
            }
            counts.append(signature.arity()).append(signature.varargs() ? "+" : "");
        }
        throw new ExpressionException(position, "Incorrect number of arguments for function '" + name + "', accepted: " + counts);
    }

    private interface NoiseCall {
        double get();
    }

    private static double noise(int position, NoiseCall call, String prefix) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new ExpressionException(position, prefix + e.getMessage());
        }
    }

    private static Node.Read variable(Node node, int position) {
        if (node instanceof Node.Read read) {
            return read;
        }
        throw new ExpressionException(position, "Argument must be a variable");
    }

    private static double rotate(Frame frame, Node[] args, int position) {
        Node.Read x = variable(args[0], position);
        Node.Read y = variable(args[1], position);
        if (x.slot == y.slot) {
            throw new ExpressionException(position, "rotate needs two different variables");
        }
        double angle = args[2].eval(frame);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double xOld = x.eval(frame);
        double yOld = y.eval(frame);
        frame.values[x.slot] = xOld * cos - yOld * sin;
        frame.values[y.slot] = xOld * sin + yOld * cos;
        return 0.0;
    }

    private static double swap(Frame frame, Node[] args, int position) {
        Node.Read x = variable(args[0], position);
        Node.Read y = variable(args[1], position);
        double tmp = x.eval(frame);
        frame.values[x.slot] = y.eval(frame);
        frame.values[y.slot] = tmp;
        return 0.0;
    }

    private static final int BUFFER_PAGES = 16384;

    private static double[] sub(Map<Integer, double[]> buffer, int index, int position) {
        int key = index & ~1023;
        double[] page = buffer.get(key);
        if (page != null) {
            return page;
        }
        if (buffer.size() >= BUFFER_PAGES) {
            throw new ExpressionException(position, "memory limit reached");
        }
        return buffer.computeIfAbsent(key, k -> new double[1024]);
    }

    private static double get(Map<Integer, double[]> buffer, int index, int position) {
        return sub(buffer, index, position)[index & 1023];
    }

    private static double set(Map<Integer, double[]> buffer, int index, double value, int position) {
        return sub(buffer, index, position)[index & 1023] = value;
    }

    private static double closest(Map<Integer, double[]> buffer, Frame frame, Node[] args, int position) {
        double x = args[0].eval(frame);
        double y = args[1].eval(frame);
        double z = args[2].eval(frame);
        int index = (int) args[3].eval(frame);
        int count = (int) args[4].eval(frame);
        int stride = (int) args[5].eval(frame);
        int closest = -1;
        double min = Double.POSITIVE_INFINITY;
        try {
            for (int i = 0; i < count; i++) {
                double dx = get(buffer, index, position) - x;
                double dy = get(buffer, Math.addExact(index, 1), position) - y;
                double dz = get(buffer, Math.addExact(index, 2), position) - z;
                double distance = dx * dx + dy * dy + dz * dz;
                if (closest == -1 || distance < min) {
                    min = distance;
                    closest = index;
                }
                if (i + 1 < count) {
                    index = Math.addExact(index, stride);
                }
            }
        } catch (ArithmeticException e) {
            throw new ExpressionException(position, "closest index is out of range");
        }
        return closest;
    }

    private static double query(Frame frame, Node[] args, int position, int mode) {
        ExprEnvironment environment = frame.owner.environment();
        if (environment == null) {
            throw new ExpressionException(position, "No world is available to query");
        }
        double x = args[0].eval(frame);
        double y = args[1].eval(frame);
        double z = args[2].eval(frame);
        double typeId = switch (mode) {
            case 1 -> environment.blockTypeAbs(x, y, z);
            case 2 -> environment.blockTypeRel(x, y, z);
            default -> environment.blockType(x, y, z);
        };
        double dataValue = switch (mode) {
            case 1 -> environment.blockDataAbs(x, y, z);
            case 2 -> environment.blockDataRel(x, y, z);
            default -> environment.blockData(x, y, z);
        };
        double type = slotValue(frame, args[3], position);
        double data = slotValue(frame, args[4], position);
        double result = (type == -1 || typeId == type) && (data == -1 || dataValue == data) ? 1.0 : 0.0;
        if (args[3] instanceof Node.Read read) {
            frame.values[read.slot] = typeId;
            frame.initialized[read.slot] = true;
        }
        if (args[4] instanceof Node.Read read) {
            frame.values[read.slot] = dataValue;
            frame.initialized[read.slot] = true;
        }
        return result;
    }

    private static double slotValue(Frame frame, Node node, int position) {
        if (node instanceof Node.Read read) {
            return frame.initialized[read.slot] ? frame.values[read.slot] : -1;
        }
        if (node instanceof Node.Num num) {
            return num.value;
        }
        throw new ExpressionException(position, "Argument must be a variable or constant");
    }
}
