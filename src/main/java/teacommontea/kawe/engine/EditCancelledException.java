package teacommontea.kawe.engine;

public final class EditCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EditCancelledException() {
        super("Cancelled", null, false, false);
    }
}
