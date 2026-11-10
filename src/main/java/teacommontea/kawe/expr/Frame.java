package teacommontea.kawe.expr;

import java.util.Arrays;

final class Frame {

    final double[] values;
    final boolean[] initialized;
    final Expression owner;
    long deadline;

    Frame(Expression owner, int slots) {
        this.owner = owner;
        this.values = new double[slots];
        this.initialized = new boolean[slots];
    }

    void reset(double[] provided, long deadlineNanos) {
        Arrays.fill(initialized, false);
        for (int i = 0; i < provided.length; i++) {
            values[i] = provided[i];
            initialized[i] = true;
        }
        this.deadline = deadlineNanos;
    }

    void checkDeadline() {
        if (System.nanoTime() > deadline) {
            throw new ExpressionTimeoutException("Calculation took too long");
        }
    }
}
