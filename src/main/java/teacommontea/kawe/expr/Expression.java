package teacommontea.kawe.expr;

import teacommontea.kawe.Settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Expression {

    private final String source;
    private final Node.Statement root;
    private final int provided;
    private final int slotCount;
    private final List<String> variables;
    private final Map<Integer, double[]> buffer = new ConcurrentHashMap<>();
    private final ThreadLocal<Frame> frames;
    private volatile ExprEnvironment environment;

    private Expression(String source, String... variables) {
        this.source = source;
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (String variable : variables) {
            if (slots.putIfAbsent(variable, slots.size()) != null) {
                throw new ExpressionException(-1, "Tried to overwrite identifier '" + variable + "'");
            }
        }
        this.provided = variables.length;
        this.variables = List.of(variables);
        this.root = Parser.parse(source, slots);
        this.slotCount = slots.size();
        this.frames = ThreadLocal.withInitial(() -> new Frame(this, slotCount));
    }

    public static Expression compile(String source, String... variables) {
        return new Expression(source, variables);
    }

    public String source() {
        return source;
    }

    public List<String> variables() {
        return variables;
    }

    public void environment(ExprEnvironment value) {
        this.environment = value;
    }

    ExprEnvironment environment() {
        return environment;
    }

    Map<Integer, double[]> buffer() {
        return buffer;
    }

    public double evaluate(double... values) {
        return evaluate(values, Settings.get().timeout());
    }

    public double evaluate(double[] values, int timeoutMillis) {
        if (values.length != provided) {
            throw new ExpressionException(-1, "Expected " + provided + " values but got " + values.length);
        }
        Frame frame = frames.get();
        frame.reset(values, timeoutMillis < 0 ? Long.MAX_VALUE : System.nanoTime() + timeoutMillis * 1_000_000L);
        double[] out = new double[1];
        try {
            if (!root.run(frame, out)) {
                throw new ExpressionException(-1, "Expression must result in a value");
            }
            return out[0];
        } catch (Node.Return value) {
            return value.value;
        } catch (Node.Signal signal) {
            throw new ExpressionException(-1, signal.proceed ? "Continue outside a loop" : "Break outside a loop");
        } catch (StackOverflowError overflow) {
            throw new ExpressionException(-1, "Expression is too deeply nested");
        }
    }

    public double read(String variable) {
        Frame frame = frames.get();
        int index = variables.indexOf(variable);
        if (index < 0) {
            throw new ExpressionException(-1, "Unknown variable '" + variable + "'");
        }
        return frame.values[index];
    }

    @Override
    public String toString() {
        return source;
    }
}
