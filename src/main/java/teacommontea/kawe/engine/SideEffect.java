package teacommontea.kawe.engine;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum SideEffect {
    HISTORY(true, true),
    HEIGHTMAPS(true, false),
    LIGHTING(true, false),
    NEIGHBORS(false, true),
    UPDATE(false, true),
    VALIDATION(false, false),
    ENTITY_AI(false, false),
    EVENTS(false, true);

    private final boolean enabledByDefault;
    private final boolean toggleable;

    SideEffect(boolean enabledByDefault, boolean toggleable) {
        this.enabledByDefault = enabledByDefault;
        this.toggleable = toggleable;
    }

    public boolean toggleable() {
        return toggleable;
    }

    public String key() {
        return "kawe.sideeffect." + name().toLowerCase(Locale.ROOT);
    }

    public static Set<SideEffect> defaults() {
        Set<SideEffect> out = EnumSet.noneOf(SideEffect.class);
        for (SideEffect effect : values()) {
            if (effect.enabledByDefault) {
                out.add(effect);
            }
        }
        return out;
    }
}
