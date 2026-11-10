package teacommontea.kawe.command;

public final class PartialCommitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int failedChunks;

    public PartialCommitException(int failedChunks, Throwable cause) {
        super(failedChunks + " chunks failed to apply", cause);
        this.failedChunks = failedChunks;
    }

    public int failedChunks() {
        return failedChunks;
    }
}
