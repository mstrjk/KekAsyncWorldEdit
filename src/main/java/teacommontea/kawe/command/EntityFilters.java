package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Golem;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.session.Actor;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class EntityFilters {

    public static final int PETS = 1;
    public static final int NPCS = 1 << 1;
    public static final int ANIMALS = 1 << 2;
    public static final int GOLEMS = 1 << 3;
    public static final int AMBIENT = 1 << 4;
    public static final int TAGGED = 1 << 5;
    public static final int ARMOR_STAND = 1 << 6;
    public static final int WATER = 1 << 7;
    public static final int FRIENDLY = PETS | NPCS | ANIMALS | GOLEMS | AMBIENT | TAGGED | WATER;

    public enum Removable {
        PROJECTILES("projectiles?|arrows?", e -> e instanceof Projectile),
        ITEMS("items?|drops?", e -> e instanceof Item),
        FALLING_BLOCKS("falling(blocks?|sand|gravel)", e -> e instanceof FallingBlock),
        PAINTINGS("paintings?|art", e -> e instanceof Painting),
        ITEM_FRAMES("(item)?frames?", e -> e instanceof ItemFrame),
        BOATS("boats?", e -> e instanceof Boat),
        MINECARTS("(mine)?carts?", e -> e instanceof Minecart),
        TNT("tnt", e -> e instanceof TNTPrimed || e instanceof ExplosiveMinecart),
        XP_ORBS("xp", e -> e instanceof ExperienceOrb),
        ALL("all", e -> false);

        private final Pattern pattern;
        private final Predicate<Entity> test;

        Removable(String pattern, Predicate<Entity> test) {
            this.pattern = Pattern.compile(pattern);
            this.test = test;
        }

        public boolean matches(Entity entity) {
            if (this != ALL) {
                return test.test(entity);
            }
            for (Removable value : values()) {
                if (value != ALL && value.test.test(entity)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final ArgType<Removable> REMOVABLE = new ArgType<>() {
        @Override
        public Removable parse(String raw, CmdContext context) throws InputParseException {
            String lower = raw.toLowerCase(Locale.ROOT);
            for (Removable value : Removable.values()) {
                if (value.pattern.matcher(lower).matches()) {
                    return value;
                }
            }
            throw new InputParseException(Component.text("Acceptable types: projectiles, items, fallingblocks, paintings, itemframes, boats, minecarts, tnt, xp, or all (every type in this list, not every entity)"));
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Stream.of("projectiles", "items", "fallingblocks", "paintings", "itemframes", "boats", "minecarts", "tnt", "xp", "all"), partial);
        }
    };

    private EntityFilters() {
    }

    private static final String[] GATES = {"kawe.butcher.pets", "kawe.butcher.npcs", "kawe.butcher.animals", "kawe.butcher.golems",
            "kawe.butcher.ambient", "kawe.butcher.tagged", "kawe.butcher.armorstands", "kawe.butcher.water"};
    private static final char[] LETTERS = {'p', 'n', 'a', 'g', 'b', 't', 'r', 'w'};

    public static int permitted(Actor actor, int requested) {
        int flags = requested;
        for (int bit = 0; bit < GATES.length; bit++) {
            if ((flags & (1 << bit)) != 0 && !actor.has(GATES[bit])) {
                flags &= ~(1 << bit);
            }
        }
        return flags;
    }

    public static String letters(int flags) {
        StringBuilder out = new StringBuilder();
        for (int bit = 0; bit < LETTERS.length; bit++) {
            if ((flags & (1 << bit)) != 0) {
                out.append(LETTERS[bit]);
            }
        }
        return out.toString();
    }

    private static int category(Entity entity) {
        if (entity instanceof Tameable tameable && tameable.isTamed()) {
            return PETS;
        }
        if (entity instanceof AbstractVillager) {
            return NPCS;
        }
        if (entity instanceof Golem) {
            return GOLEMS;
        }
        if (entity instanceof ArmorStand) {
            return ARMOR_STAND;
        }
        if (entity instanceof Ambient) {
            return AMBIENT;
        }
        if (entity instanceof WaterMob) {
            return WATER;
        }
        if (entity instanceof Animals) {
            return ANIMALS;
        }
        return 0;
    }

    public static boolean butcherable(Entity entity, int flags) {
        if (entity instanceof HumanEntity || !(entity instanceof LivingEntity living)) {
            return false;
        }
        if ((flags & TAGGED) == 0 && living.customName() != null) {
            return false;
        }
        int category = category(entity);
        return category == 0 || (flags & category) != 0;
    }
}
