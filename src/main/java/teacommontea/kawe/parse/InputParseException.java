package teacommontea.kawe.parse;

import net.kyori.adventure.text.Component;
import teacommontea.kawe.text.Msg;

public final class InputParseException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Component message;

    public InputParseException(Component message) {
        super(null, null, false, false);
        this.message = message;
    }

    public static InputParseException of(String key, Object... args) {
        return new InputParseException(Msg.tr(key, args));
    }

    public Component component() {
        return message;
    }
}
