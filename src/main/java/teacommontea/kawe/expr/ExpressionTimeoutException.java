package teacommontea.kawe.expr;

public final class ExpressionTimeoutException extends ExpressionException {

    private static final long serialVersionUID = 1L;

    public ExpressionTimeoutException(String message) {
        super(-1, message);
    }
}
