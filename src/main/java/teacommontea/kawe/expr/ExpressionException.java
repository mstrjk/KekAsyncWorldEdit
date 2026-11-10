package teacommontea.kawe.expr;

public class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;

    public ExpressionException(int position, String message) {
        super(message, null, false, false);
        this.position = position;
    }

    public int position() {
        return position;
    }

    @Override
    public String getMessage() {
        return position < 0 ? super.getMessage() : super.getMessage() + " at position " + position;
    }
}
