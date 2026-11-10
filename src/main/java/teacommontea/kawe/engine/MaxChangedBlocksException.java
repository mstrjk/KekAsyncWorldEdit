package teacommontea.kawe.engine;

public final class MaxChangedBlocksException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long limit;

    public MaxChangedBlocksException(long limit) {
        super(null, null, false, false);
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}
