package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import teacommontea.kawe.text.Msg;

public final class CommandError extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Component message;

    public CommandError(String key, Object... args) {
        super(key, null, false, false);
        this.message = Msg.tr(key, args);
    }

    public CommandError(Component message) {
        super(null, null, false, false);
        this.message = message;
    }

    public Component component() {
        return message;
    }
}
