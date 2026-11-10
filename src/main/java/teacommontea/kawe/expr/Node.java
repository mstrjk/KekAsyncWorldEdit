package teacommontea.kawe.expr;

import java.util.List;

abstract class Node {

    final int position;

    Node(int position) {
        this.position = position;
    }

    abstract double eval(Frame frame);

    static boolean truth(double value) {
        return value != 0;
    }

    static double bool(boolean value) {
        return value ? 1 : 0;
    }

    static final class Signal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final Signal BREAK = new Signal(false);
        static final Signal CONTINUE = new Signal(true);
        final boolean proceed;

        private Signal(boolean proceed) {
            super(null, null, false, false);
            this.proceed = proceed;
        }
    }

    static final class Return extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final double value;

        Return(double value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    static final class Num extends Node {
        final double value;

        Num(int position, double value) {
            super(position);
            this.value = value;
        }

        @Override
        double eval(Frame frame) {
            return value;
        }
    }

    static final class Read extends Node {
        final int slot;
        final String name;

        Read(int position, int slot, String name) {
            super(position);
            this.slot = slot;
            this.name = name;
        }

        @Override
        double eval(Frame frame) {
            if (!frame.initialized[slot]) {
                throw new ExpressionException(position, "'" + name + "' is not initialized yet");
            }
            return frame.values[slot];
        }
    }

    static final class Assign extends Node {
        final int slot;
        final String name;
        final String op;
        final Node source;

        Assign(int position, int slot, String name, String op, Node source) {
            super(position);
            this.slot = slot;
            this.name = name;
            this.op = op;
            this.source = source;
        }

        @Override
        double eval(Frame frame) {
            double value = source.eval(frame);
            if (!op.equals("=")) {
                if (!frame.initialized[slot]) {
                    throw new ExpressionException(position, "'" + name + "' is not initialized yet");
                }
                double current = frame.values[slot];
                value = switch (op) {
                    case "+=" -> current + value;
                    case "-=" -> current - value;
                    case "*=" -> current * value;
                    case "/=" -> current / value;
                    case "%=" -> current % value;
                    case "^=" -> Math.pow(current, value);
                    default -> throw new ExpressionException(position, "Invalid assignment operator " + op);
                };
            }
            frame.values[slot] = value;
            frame.initialized[slot] = true;
            return value;
        }
    }

    static final class Step extends Node {
        final int slot;
        final String name;
        final int delta;
        final boolean prefix;

        Step(int position, int slot, String name, int delta, boolean prefix) {
            super(position);
            this.slot = slot;
            this.name = name;
            this.delta = delta;
            this.prefix = prefix;
        }

        @Override
        double eval(Frame frame) {
            if (!frame.initialized[slot]) {
                throw new ExpressionException(position, "'" + name + "' is not initialized yet");
            }
            double before = frame.values[slot];
            double after = before + delta;
            frame.values[slot] = after;
            return prefix ? after : before;
        }
    }

    static final class Unary extends Node {
        final char op;
        final Node operand;

        Unary(int position, char op, Node operand) {
            super(position);
            this.op = op;
            this.operand = operand;
        }

        @Override
        double eval(Frame frame) {
            double v = operand.eval(frame);
            return switch (op) {
                case '-' -> -v;
                case '!' -> bool(!truth(v));
                case '~' -> (double) ~(long) v;
                default -> v;
            };
        }
    }

    static final class Factorial extends Node {
        private static final double[] TABLE = new double[171];

        static {
            TABLE[0] = 1;
            for (int i = 1; i < TABLE.length; i++) {
                TABLE[i] = TABLE[i - 1] * i;
            }
        }

        final Node operand;

        Factorial(int position, Node operand) {
            super(position);
            this.operand = operand;
        }

        @Override
        double eval(Frame frame) {
            double value = operand.eval(frame);
            if (Double.isNaN(value)) {
                return Double.NaN;
            }
            int n = (int) value;
            if (n < 0) {
                return 0;
            }
            return n >= TABLE.length ? Double.POSITIVE_INFINITY : TABLE[n];
        }
    }

    static final class Binary extends Node {
        final String op;
        final Node left;
        final Node right;

        Binary(int position, String op, Node left, Node right) {
            super(position);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        double eval(Frame frame) {
            double l = left.eval(frame);
            double r = right.eval(frame);
            return switch (op) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> l / r;
                case "%" -> l % r;
                case "^", "**" -> Math.pow(l, r);
                case "<<" -> (double) ((long) l << (long) r);
                case ">>" -> (double) ((long) l >> (long) r);
                case "<" -> bool(l < r);
                case "<=" -> bool(l <= r);
                case ">" -> bool(l > r);
                case ">=" -> bool(l >= r);
                case "==" -> bool(l == r);
                case "!=" -> bool(l != r);
                case "~=" -> bool(almostEqual(l, r));
                default -> throw new ExpressionException(position, "Invalid operator " + op);
            };
        }

        private static boolean almostEqual(double a, double b) {
            long aLong = Double.doubleToRawLongBits(a);
            if (aLong < 0) {
                aLong = 0x8000000000000000L - aLong;
            }
            long bLong = Double.doubleToRawLongBits(b);
            if (bLong < 0) {
                bLong = 0x8000000000000000L - bLong;
            }
            long diff = aLong - bLong;
            if (((aLong ^ bLong) & (aLong ^ diff)) < 0) {
                return false;
            }
            return Math.abs(diff) <= 450359963L;
        }
    }

    static final class And extends Node {
        final Node left;
        final Node right;

        And(int position, Node left, Node right) {
            super(position);
            this.left = left;
            this.right = right;
        }

        @Override
        double eval(Frame frame) {
            return truth(left.eval(frame)) ? right.eval(frame) : 0;
        }
    }

    static final class Or extends Node {
        final Node left;
        final Node right;

        Or(int position, Node left, Node right) {
            super(position);
            this.left = left;
            this.right = right;
        }

        @Override
        double eval(Frame frame) {
            double l = left.eval(frame);
            return truth(l) ? l : right.eval(frame);
        }
    }

    static final class Ternary extends Node {
        final Node condition;
        final Node yes;
        final Node no;

        Ternary(int position, Node condition, Node yes, Node no) {
            super(position);
            this.condition = condition;
            this.yes = yes;
            this.no = no;
        }

        @Override
        double eval(Frame frame) {
            return truth(condition.eval(frame)) ? yes.eval(frame) : no.eval(frame);
        }
    }

    static final class Call extends Node {
        final Functions.Function function;
        final Node[] args;

        Call(int position, Functions.Function function, Node[] args) {
            super(position);
            this.function = function;
            this.args = args;
        }

        @Override
        double eval(Frame frame) {
            return function.apply(frame, args, position);
        }
    }

    abstract static class Statement extends Node {
        Statement(int position) {
            super(position);
        }

        abstract boolean run(Frame frame, double[] out);

        @Override
        double eval(Frame frame) {
            double[] out = new double[1];
            if (!run(frame, out)) {
                throw new ExpressionException(position, "Expression must result in a value");
            }
            return out[0];
        }
    }

    static final class ExpressionStatement extends Statement {
        final Node expression;

        ExpressionStatement(int position, Node expression) {
            super(position);
            this.expression = expression;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            out[0] = expression.eval(frame);
            return true;
        }
    }

    static final class Empty extends Statement {
        Empty(int position) {
            super(position);
        }

        @Override
        boolean run(Frame frame, double[] out) {
            return false;
        }
    }

    static final class Sequence extends Statement {
        final Statement[] body;

        Sequence(int position, List<Statement> body) {
            super(position);
            this.body = body.toArray(Statement[]::new);
        }

        @Override
        boolean run(Frame frame, double[] out) {
            boolean has = false;
            for (Statement statement : body) {
                has = statement.run(frame, out);
            }
            return has;
        }
    }

    static final class If extends Statement {
        final Node condition;
        final Statement yes;
        final Statement no;

        If(int position, Node condition, Statement yes, Statement no) {
            super(position);
            this.condition = condition;
            this.yes = yes;
            this.no = no;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            if (truth(condition.eval(frame))) {
                return yes.run(frame, out);
            }
            return no != null && no.run(frame, out);
        }
    }

    private static void iteration(Frame frame, int count, int position) {
        if (count >= 256) {
            throw new ExpressionException(position, "Loop exceeded 256 iterations");
        }
        frame.checkDeadline();
    }

    static final class While extends Statement {
        final Node init;
        final Node condition;
        final Node update;
        final Statement body;
        final boolean checkFirst;

        While(int position, Node init, Node condition, Node update, Statement body, boolean checkFirst) {
            super(position);
            this.init = init;
            this.condition = condition;
            this.update = update;
            this.body = body;
            this.checkFirst = checkFirst;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            boolean has = false;
            int count = 0;
            if (init != null) {
                init.eval(frame);
            }
            if (!checkFirst) {
                iteration(frame, count++, position);
                try {
                    has = body.run(frame, out);
                } catch (Signal signal) {
                    if (!signal.proceed) {
                        return has;
                    }
                }
            }
            while (truth(condition.eval(frame))) {
                iteration(frame, count++, position);
                try {
                    has = body.run(frame, out);
                } catch (Signal signal) {
                    if (!signal.proceed) {
                        break;
                    }
                }
                if (update != null) {
                    update.eval(frame);
                }
            }
            return has;
        }
    }

    static final class SimpleFor extends Statement {
        final int slot;
        final Node first;
        final Node last;
        final Statement body;

        SimpleFor(int position, int slot, Node first, Node last, Statement body) {
            super(position);
            this.slot = slot;
            this.first = first;
            this.last = last;
            this.body = body;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            boolean has = false;
            double from = first.eval(frame);
            double to = last.eval(frame);
            frame.initialized[slot] = true;
            int count = 0;
            for (double i = from; i <= to; i++) {
                iteration(frame, count++, position);
                frame.values[slot] = i;
                try {
                    has = body.run(frame, out);
                } catch (Signal signal) {
                    if (!signal.proceed) {
                        break;
                    }
                }
            }
            return has;
        }
    }

    static final class Jump extends Statement {
        final Signal signal;

        Jump(int position, Signal signal) {
            super(position);
            this.signal = signal;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            throw signal;
        }
    }

    static final class ReturnStatement extends Statement {
        final Node value;

        ReturnStatement(int position, Node value) {
            super(position);
            this.value = value;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            throw new Return(value.eval(frame));
        }
    }

    static final class Switch extends Statement {
        final Node target;
        final double[] keys;
        final Statement[] bodies;
        final int fallback;

        Switch(int position, Node target, double[] keys, Statement[] bodies, int fallback) {
            super(position);
            this.target = target;
            this.keys = keys;
            this.bodies = bodies;
            this.fallback = fallback;
        }

        @Override
        boolean run(Frame frame, double[] out) {
            double value = target.eval(frame);
            int start = fallback;
            for (int i = 0; i < keys.length; i++) {
                if (i != fallback && keys[i] == value) {
                    start = i;
                    break;
                }
            }
            boolean has = false;
            if (start < 0) {
                return false;
            }
            for (int i = start; i < bodies.length; i++) {
                try {
                    has = bodies[i].run(frame, out);
                } catch (Signal signal) {
                    if (signal.proceed) {
                        throw new ExpressionException(bodies[i].position, "Cannot continue in a switch");
                    }
                    break;
                }
            }
            return has;
        }
    }
}
