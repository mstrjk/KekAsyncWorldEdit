package teacommontea.kawe.tool;

import teacommontea.kawe.command.CmdContext;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.function.Pattern;
import teacommontea.kawe.math.BlockVec;

import java.util.ArrayList;
import java.util.List;

public final class BrushUse {

    @FunctionalInterface
    public interface AfterCommit {
        void run(Edit.Result result) throws Exception;
    }

    private final CmdContext context;
    private final Edit edit;
    private final BlockVec target;
    private final Pattern pattern;
    private final double size;
    private final List<AfterCommit> after = new ArrayList<>();

    BrushUse(CmdContext context, Edit edit, BlockVec target, Pattern pattern, double size) {
        this.context = context;
        this.edit = edit;
        this.target = target;
        this.pattern = pattern;
        this.size = size;
    }

    public CmdContext context() {
        return context;
    }

    public Edit edit() {
        return edit;
    }

    public BlockVec target() {
        return target;
    }

    public Pattern pattern() {
        return pattern;
    }

    public double size() {
        return size;
    }

    public void afterCommit(AfterCommit action) {
        after.add(action);
    }

    List<AfterCommit> after() {
        return after;
    }
}
