package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.World;
import teacommontea.kawe.Settings;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.color.BlockColors;
import teacommontea.kawe.engine.SideEffect;
import teacommontea.kawe.op.Generation;
import teacommontea.kawe.parse.BlockMatcher;
import teacommontea.kawe.parse.Input;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.parse.Masks;
import teacommontea.kawe.parse.Transforms;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class GeneralCommands {

    static final ArgType<World> WORLD = new ArgType<>() {
        @Override
        public World parse(String raw, CmdContext context) throws InputParseException {
            for (World world : Bukkit.getWorlds()) {
                if (id(world).equals(raw.toLowerCase(Locale.ROOT)) || world.getName().equalsIgnoreCase(raw)) {
                    return world;
                }
            }
            throw InputParseException.of("kawe.error.no-match", raw);
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Bukkit.getWorlds().stream().flatMap(world -> world.getName().equals(id(world))
                    ? Stream.of(id(world)) : Stream.of(id(world), world.getName())), partial);
        }
    };

    private GeneralCommands() {
    }

    static String id(World world) {
        return world.getName().replace(" ", "_").toLowerCase(Locale.ROOT);
    }

    public static void register() {
        Commands.add(Cmd.of("/world").perm("kawe.world").desc("Sets the world override").immediate()
                .opt("world", WORLD, "").run(GeneralCommands::world));
        Commands.add(Cmd.of("toggleplace").aliases("/toggleplace").perm("kawe.toggleplace").desc("Switch between your position and pos1 for placement").immediate()
                .run(GeneralCommands::togglePlace));
        Commands.add(Cmd.of("/timeout").perm("kawe.timeout").desc("Modify evaluation timeout time.").immediate()
                .opt("limit", Types.INT, "").run(GeneralCommands::timeout));
        Commands.add(Cmd.of("/limit").perm("kawe.limit").desc("Modify block change limit").immediate()
                .opt("limit", Types.INT, "").run(GeneralCommands::limit));
        Commands.add(Cmd.of("gmask").aliases("/gmask").perm("kawe.global-mask").desc("Set the global mask").immediate()
                .opt("mask", Types.TEXT, "").run(c -> globalMask(c, false)));
        Commands.add(Cmd.of("/gsmask").aliases("gsmask", "globalsourcemask", "/globalsourcemask").perm("kawe.global-mask")
                .desc("Set the global source mask").immediate().opt("mask", Types.TEXT, "").run(c -> globalMask(c, true)));
        Commands.add(Cmd.of("/gtransform").aliases("gtransform").perm("kawe.global-transform").desc("Set the global transform")
                .opt("transform", Types.TEXT, "").run(GeneralCommands::gtransform));
        Commands.add(Cmd.of("/registry").perm("kawe.registry").desc("Search through the given registry")
                .arg("registry", REGISTRY).flag('p', "Page of results to return", Types.INT).opt("query", Types.TEXT, "*").run(GeneralCommands::registry));
        Commands.add(Cmd.of("/gtexture").aliases("gtexture").perm("kawe.global-texture").desc("Set the global texture palette")
                .opt("arguments", Types.TEXT, "").run(GeneralCommands::gtexture));
        Commands.add(Cmd.of("/fast").perm("kawe.fast").desc("Toggle fast mode").immediate()
                .opt("enabled", Types.BOOL, "").run(GeneralCommands::fast));
        Commands.add(Cmd.of("/tips").aliases("tips").perm("kawe.tips").desc("Toggle KAWE tips").immediate().run(GeneralCommands::tips));
        Commands.add(Cmd.of("/perf").perm("kawe.perf").desc("Toggle side effects for performance").immediate()
                .opt("sideEffect", SIDE_EFFECT, "").opt("state", Types.BOOL, "").flag('h', "Show the info box").run(GeneralCommands::perf));
        Commands.add(Cmd.of("/watchdog").perm("kawe.watchdog").desc("Changes watchdog hook state.").immediate()
                .opt("mode", Types.WORD, "").run(c -> c.print("kawe.watchdog.no-hook")));
        Commands.add(Cmd.of("searchitem").aliases("/searchitem", "/l", "/search").perm("kawe.searchitem").desc("Search for an item")
                .flag('b', "Only search for blocks").flag('i', "Only search for items").flag('p', "Page of results to return", Types.INT)
                .arg("query", Types.TEXT).run(GeneralCommands::searchItem));
    }

    static final ArgType<SideEffect> SIDE_EFFECT = new ArgType<>() {
        @Override
        public SideEffect parse(String raw, CmdContext context) throws InputParseException {
            try {
                return SideEffect.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw InputParseException.of("kawe.error.no-match", raw);
            }
        }

        @Override
        public boolean accepts(String raw) {
            for (SideEffect effect : SideEffect.values()) {
                if (effect.name().equalsIgnoreCase(raw)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Arrays.stream(SideEffect.values()).map(e -> e.name().toLowerCase(Locale.ROOT)), partial);
        }
    };

    private static void limit(CmdContext c) throws InputParseException, CommandError {
        Settings settings = Settings.get();
        Integer requested = c.get("limit", Types.INT);
        if (requested != null && requested < -1) {
            throw new CommandError(Component.text("Limit must be -1 (unlimited) or higher"));
        }
        int limit = requested == null ? settings.defaultChangeLimit() : requested;
        if (!c.actor().has("kawe.limit.unrestricted") && settings.maxChangeLimit() > -1
                && (limit > settings.maxChangeLimit() || limit == -1)) {
            c.print("kawe.limit.too-high", settings.maxChangeLimit());
            return;
        }
        c.session().limit(limit);
        Component message = Msg.tr("kawe.limit.set", limit);
        if (limit != settings.defaultChangeLimit()) {
            message = message.append(Component.space()).append(Msg.tr("kawe.limit.return-to-default"));
        }
        c.print(message);
    }

    private static void globalMask(CmdContext c, boolean source) throws InputParseException, CommandError {
        String input = c.get("mask", Types.TEXT);
        if (input != null) {
            Masks.parse(input, c.parseContext(c.reader()));
        }
        if (source) {
            c.session().sourceMaskInput(input);
            c.print(input == null ? "kawe.general.source.mask.disabled" : "kawe.general.source.mask");
        } else {
            c.session().maskInput(input);
            c.print(input == null ? "kawe.gmask.disabled" : "kawe.gmask.set");
        }
    }

    private static void fast(CmdContext c) throws InputParseException {
        Boolean requested = c.get("enabled", Types.BOOL);
        boolean current = c.session().fast();
        if (requested != null && requested == current) {
            c.print(current ? "kawe.fast.enabled.already" : "kawe.fast.disabled.already");
            return;
        }
        boolean next = requested != null ? requested : !current;
        c.session().fast(next);
        c.print(next ? "kawe.fast.enabled" : "kawe.fast.disabled");
    }

    private static void tips(CmdContext c) {
        boolean enabled = !c.session().tips();
        c.session().tips(enabled);
        c.print(enabled ? "kawe.info.toggle.tips.off" : "kawe.info.toggle.tips.on");
    }

    private static Component stateName(boolean on) {
        return Msg.tr(on ? "kawe.sideeffect.state.on" : "kawe.sideeffect.state.off");
    }

    private static void perf(CmdContext c) throws InputParseException, CommandError {
        SideEffect effect = c.get("sideEffect", SIDE_EFFECT);
        Boolean state = c.get("state", Types.BOOL);
        boolean quiet = c.flag('h');
        if (effect != null) {
            boolean current = c.session().sideEffects().contains(effect);
            if (state == null) {
                c.print("kawe.perf.sideeffect.get", Msg.tr(effect.key()), stateName(current));
            } else if (!effect.toggleable()) {
                throw new CommandError("kawe.error.disabled");
            } else if (state == current) {
                if (!quiet) {
                    c.print("kawe.perf.sideeffect.already-set", Msg.tr(effect.key()), stateName(state));
                }
            } else {
                c.session().sideEffect(effect, state);
                if (!quiet) {
                    c.print("kawe.perf.sideeffect.set", Msg.tr(effect.key()), stateName(state));
                }
            }
        } else if (state != null) {
            for (SideEffect each : SideEffect.values()) {
                if (each.toggleable()) {
                    c.session().sideEffect(each, state);
                }
            }
            if (!quiet) {
                c.print("kawe.perf.sideeffect.set-all", stateName(state));
            }
        }
        if (effect == null || quiet) {
            List<Component> lines = new ArrayList<>();
            for (SideEffect each : SideEffect.values()) {
                boolean on = c.session().sideEffects().contains(each);
                Component line = Msg.tr(each.key()).hoverEvent(HoverEvent.showText(Msg.tr(each.key() + ".description")))
                        .append(Component.text(": ")).append(stateName(on).color(on ? NamedTextColor.GREEN : NamedTextColor.RED));
                if (each.toggleable()) {
                    String command = "//perf -h " + each.name().toLowerCase(Locale.ROOT) + " " + !on;
                    line = line.append(Component.space()).append(Component.text(on ? "[off]" : "[on]", NamedTextColor.GRAY)
                            .clickEvent(ClickEvent.runCommand(command))
                            .hoverEvent(HoverEvent.showText(Msg.tr("kawe.sideeffect.box.change-to", stateName(!on)))));
                }
                lines.add(line);
            }
            c.print(new PaginationBox("Side Effects", null, lines).perPage(lines.size()).create(1));
        }
    }

    private static void searchItem(CmdContext c) throws InputParseException {
        String search = c.get("query", Types.TEXT);
        if (search.length() <= 2) {
            c.print("kawe.searchitem.too-short");
            return;
        }
        boolean blocks = c.flag('b');
        boolean items = c.flag('i');
        if (blocks && items) {
            c.print("kawe.searchitem.either-b-or-i");
            return;
        }
        String match = search.replace(' ', '_').toLowerCase(Locale.ROOT);
        Map<String, Component> results = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            boolean block = item instanceof BlockItem;
            if ((blocks && !block) || (items && block)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (id.contains(match)) {
                results.put(id, Component.translatable(item.getDescriptionId()).append(Component.text(" (" + id + ")")));
            }
        }
        Integer page = c.flagValue('p', Types.INT);
        String command = "/searchitem " + (blocks ? "-b " : "") + (items ? "-i " : "") + "-p %page% " + search;
        c.print(new PaginationBox("Search results for '" + search + "'", command, new ArrayList<>(results.values()))
                .forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
    }

    private static void timeout(CmdContext c) throws InputParseException, CommandError {
        Settings settings = Settings.get();
        Integer requested = c.get("limit", Types.INT);
        if (requested != null && requested < -1) {
            throw new CommandError(Component.text("Timeout must be -1 (none) or higher"));
        }
        int limit = requested == null ? settings.timeout() : requested;
        if (!c.actor().has("kawe.timeout.unrestricted") && settings.maxTimeout() > -1
                && (limit > settings.maxTimeout() || limit == -1)) {
            c.print("kawe.timeout.too-high", settings.maxTimeout());
            return;
        }
        c.session().timeout(limit);
        Component message = Msg.tr("kawe.timeout.set", limit);
        if (limit != settings.timeout()) {
            message = message.append(Msg.tr("kawe.timeout.return-to-default"));
        }
        c.print(message);
    }

    private static void togglePlace(CmdContext c) {
        boolean pos1 = !c.session().placeAtPos1();
        if (!pos1 && !c.actor().isPlayer()) {
            c.print("kawe.toggleplace.not-locatable");
            return;
        }
        c.session().placeAtPos1(pos1);
        c.print(pos1 ? "kawe.toggleplace.pos1" : "kawe.toggleplace.player");
    }

    private static void world(CmdContext c) throws InputParseException {
        World world = c.get("world", WORLD);
        c.session().worldOverride(world);
        if (world == null) {
            c.print("kawe.world.remove");
        } else {
            c.print("kawe.world.set", id(world));
        }
    }

    private static final Pattern REGISTRY_KEY = Pattern.compile("[a-z0-9_:?*/]+");

    private record RegistryEntry(String id, Component name) {
    }

    private static final Map<String, Supplier<List<RegistryEntry>>> REGISTRIES = registries();

    private static Map<String, Supplier<List<RegistryEntry>>> registries() {
        Map<String, Supplier<List<RegistryEntry>>> out = new TreeMap<>();
        out.put("block_type", () -> BuiltInRegistries.BLOCK.stream()
                .map(block -> new RegistryEntry(BuiltInRegistries.BLOCK.getKey(block).toString(), Component.translatable(block.getDescriptionId()))).toList());
        out.put("item_type", () -> BuiltInRegistries.ITEM.stream()
                .map(item -> new RegistryEntry(BuiltInRegistries.ITEM.getKey(item).toString(), Component.translatable(item.getDescriptionId()))).toList());
        out.put("entity_type", () -> BuiltInRegistries.ENTITY_TYPE.stream()
                .map(type -> new RegistryEntry(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), Component.translatable(type.getDescriptionId()))).toList());
        out.put("fluid_type", () -> keys(BuiltInRegistries.FLUID));
        out.put("block_tag", () -> tags(BuiltInRegistries.BLOCK));
        out.put("item_tag", () -> tags(BuiltInRegistries.ITEM));
        out.put("fluid_tag", () -> tags(BuiltInRegistries.FLUID));
        out.put("biome_type", () -> {
            Registry<Biome> biomes = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
            return biomes.keySet().stream().map(id -> new RegistryEntry(id.toString(),
                    Component.translatable("biome." + id.getNamespace() + "." + id.getPath()))).toList();
        });
        out.put("biome_tag", () -> tags(MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME)));
        out.put("game_mode", () -> Arrays.stream(GameType.values()).map(type -> new RegistryEntry(type.getName(), Component.text(type.getName()))).toList());
        out.put("weather_type", () -> Stream.of("clear", "rain", "thunder_storm").map(id -> new RegistryEntry(id, Component.text(id))).toList());
        out.put("configured_feature_type", () -> keys(Generation.features(MinecraftServer.getServer().registryAccess())));
        out.put("structure_type", () -> keys(Generation.structures(MinecraftServer.getServer().registryAccess())));
        return out;
    }

    private static List<RegistryEntry> keys(Registry<?> registry) {
        return registry.keySet().stream().map(id -> new RegistryEntry(id.toString(), Component.text(id.toString()))).toList();
    }

    private static List<RegistryEntry> tags(Registry<?> registry) {
        return registry.getTags().map(tag -> tag.key().location().toString()).map(id -> new RegistryEntry(id, Component.text("#" + id))).toList();
    }

    private static final ArgType<String> REGISTRY = new ArgType<>() {
        @Override
        public String parse(String raw, CmdContext context) throws InputParseException {
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (!REGISTRIES.containsKey(id)) {
                throw new InputParseException(Component.text("Unknown registry: " + raw + " (" + String.join(", ", REGISTRIES.keySet()) + ")"));
            }
            return id;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(REGISTRIES.keySet().stream(), partial);
        }
    };

    private static void registry(CmdContext c) throws Exception {
        String id = c.get("registry", REGISTRY);
        Integer page = c.flagValue('p', Types.INT);
        String raw = c.raw("query");
        String query = raw == null || raw.isBlank() ? "*" : String.join("_", raw.trim().toLowerCase(Locale.ROOT).split("\\s+"));
        if (!REGISTRY_KEY.matcher(query).matches()) {
            c.error("kawe.registry.error.invalid-key", query);
            return;
        }
        c.print("kawe.registry.searching", query);
        String glob = query.contains("*") || query.contains("?") ? query : "*" + query + "*";
        Pattern matcher = Pattern.compile("^" + glob.replace("*", ".*").replace("?", ".") + "$");
        Map<String, Component> results = new TreeMap<>();
        for (RegistryEntry entry : REGISTRIES.get(id).get()) {
            if (matcher.matcher(entry.id()).matches()) {
                results.put(entry.id(), Component.text(entry.id()).clickEvent(ClickEvent.copyToClipboard(entry.id())).hoverEvent(HoverEvent.showText(entry.name())));
            }
        }
        String title = query.equals("*") ? "Registry contents" : "Search results for '" + query + "'";
        try {
            c.print(new PaginationBox(title, "//registry " + id + " -p %page% " + query, new ArrayList<>(results.values()))
                    .forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    private static void gtransform(CmdContext c) throws Exception {
        String raw = c.raw("transform");
        if (raw == null || raw.isBlank()) {
            c.session().transformInput(null);
            c.print("kawe.general.transform.disabled");
            return;
        }
        Transforms.parse(raw, c.parseContext());
        c.session().transformInput(raw.trim());
        c.print("kawe.general.transform");
    }

    private static void gtexture(CmdContext c) throws Exception {
        String raw = c.raw("arguments");
        if (raw == null || raw.isBlank()) {
            c.session().palette(null);
            c.print("kawe.general.texture.disabled");
            return;
        }
        String[] args = raw.trim().split("\\s+");
        BlockColors.Palette palette = BlockColors.all();
        int randomIndex = 1;
        String first = args[0].toLowerCase(Locale.ROOT);
        if (args.length >= 2 && Types.INT.accepts(args[0]) && Types.INT.accepts(args[1])) {
            int min = Integer.parseInt(args[0]);
            int max = Integer.parseInt(args[1]);
            if (min < 0 || max > 100 || min > max) {
                throw new CommandError("kawe.error.too-simple");
            }
            palette = palette.varianceRange(min, max);
            randomIndex = 2;
        } else if (args.length == 1 && (first.equals("true") || first.equals("false"))) {
            palette = palette.randomized(first.equals("true"));
            randomIndex = Integer.MAX_VALUE;
        } else if (first.equals("#copy") || first.equals("#clipboard")) {
            Clipboard clipboard = c.clipboard().clipboard();
            Set<Block> used = new HashSet<>();
            clipboard.region().forEach((x, y, z) -> used.add(clipboard.get(x, y, z).getBlock()));
            palette = palette.filter(entry -> used.contains(entry.block()));
        } else if (!first.equals("*")) {
            BitSet states = new BitSet();
            for (Input.Entry entry : Input.split(args[0])) {
                states.or(BlockMatcher.parse(entry.full(), c.parseContext()));
            }
            palette = palette.filter(entry -> states.get(Block.getId(entry.block().defaultBlockState())));
        }
        if (randomIndex < args.length) {
            palette = palette.randomized(Types.BOOL.parse(args[randomIndex], c));
        }
        if (palette.isEmpty()) {
            throw new CommandError("kawe.error.no-block-found", raw);
        }
        c.session().palette(palette);
        c.print("kawe.general.texture.set", "", raw);
    }
}
