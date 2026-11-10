package teacommontea.kawe.region;

import net.kyori.adventure.text.Component;
import teacommontea.kawe.text.Msg;

public final class RegionOperationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient Component message;

    public RegionOperationException(String key, Object... args) {
        super(key, null, false, false);
        this.message = Msg.tr(key, args);
    }

    public Component component() {
        return message;
    }
}
