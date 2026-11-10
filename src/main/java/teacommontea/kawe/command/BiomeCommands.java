package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import teacommontea.kawe.engine.ChunkKey;
import teacommontea.kawe.engine.Edit;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.op.Ops;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.region.CuboidRegion;
import teacommontea.kawe.region.Region;
import teacommontea.kawe.session.Navigation;
import teacommontea.kawe.text.PaginationBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BiomeCommands {

    private static Registry<Biome> registry() {
        return MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
    }

    static final ArgType<Holder<Biome>> BIOME = new ArgType<>() {
        @Override
        public Holder<Biome> parse(String raw, CmdContext context) throws InputParseException {
            Identifier id = Identifier.tryParse(raw.toLowerCase(Locale.ROOT));
            Holder<Biome> holder = id == null ? null : registry().get(ResourceKey.create(Registries.BIOME, id)).orElse(null);
            if (holder == null) {
                throw InputParseException.of("kawe.error.unknown-biome", raw);
            }
            return holder;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(registry().keySet().stream().map(Identifier::toString), partial);
        }
    };

    private BiomeCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("biomelist").aliases("biomels", "/biomelist", "/listbiomes").perm("kawe.biome.list").desc("Gets all biomes available.")
                .flag('p', "Page number.", Types.INT).run(BiomeCommands::list));
        Commands.add(Cmd.of("biomeinfo").perm("kawe.biome.info").desc("Get the biome of the targeted block.")
                .flag('t', "Use the block you are looking at.").flag('p', "Use the block you are currently in.").run(BiomeCommands::info));
        Commands.add(Cmd.of("/setbiome").confirm(Confirm.REGION).perm("kawe.biome.set").desc("Sets the biome of your current block or region.")
                .arg("biome", BIOME).flag('p', "Use your current position").run(BiomeCommands::set));
    }

    private static Component richName(Holder<Biome> biome) {
        return biome.unwrapKey()
                .<Component>map(key -> Component.translatable("biome." + key.identifier().getNamespace() + "." + key.identifier().getPath()))
                .orElseGet(() -> Component.text(biome.toString()));
    }

    private static String id(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
    }

    private static void list(CmdContext c) throws Exception {
        Integer page = c.flagValue('p', Types.INT);
        List<Component> lines = new ArrayList<>();
        registry().listElements().sorted(Comparator.comparing(BiomeCommands::id)).forEach(biome ->
                lines.add(Component.text(id(biome) + " (").append(richName(biome)).append(Component.text(")"))));
        c.print(new PaginationBox("Available Biomes", "/biomelist -p %page%", lines).forConsole(!c.actor().isPlayer()).create(page == null ? 1 : page));
    }

    private static void info(CmdContext c) throws Exception {
        Edit reader = c.reader();
        Set<Holder<Biome>> biomes = new LinkedHashSet<>();
        String key;
        if (c.flag('t')) {
            if (!c.actor().isPlayer()) {
                c.print("kawe.raytrace.require-player");
                return;
            }
            BlockVec hit = Navigation.blockTrace(reader, c.actor().eye(), c.actor().yaw(), c.actor().pitch(), 300);
            if (hit == null) {
                c.print("kawe.raytrace.noblock");
                return;
            }
            Holder<Biome> biome = reader.biome(hit.x(), hit.y(), hit.z());
            if (biome != null) {
                biomes.add(biome);
            }
            key = "kawe.biomeinfo.lineofsight";
        } else if (c.flag('p')) {
            if (!c.actor().isPlayer()) {
                c.print("kawe.biomeinfo.not-locatable");
                return;
            }
            BlockVec at = c.actor().block();
            Holder<Biome> biome = reader.biome(at.x(), at.y(), at.z());
            if (biome != null) {
                biomes.add(biome);
            }
            key = "kawe.biomeinfo.position";
        } else {
            Region region = c.selection();
            Set<Holder<Biome>> found = ConcurrentHashMap.newKeySet();
            Ops.parallelChunks(reader, region, chunk -> region.forEachInChunk(ChunkKey.x(chunk), ChunkKey.z(chunk), (x, y, z) -> {
                Holder<Biome> biome = reader.biome(x, y, z);
                if (biome != null) {
                    found.add(biome);
                }
            }));
            found.stream().sorted(Comparator.comparing(BiomeCommands::id)).forEach(biomes::add);
            key = "kawe.biomeinfo.selection";
        }
        List<Component> names = new ArrayList<>();
        for (Holder<Biome> biome : biomes) {
            names.add(richName(biome).hoverEvent(HoverEvent.showText(Component.text(id(biome)))));
        }
        c.print(key, Component.join(JoinConfiguration.separator(Component.text(", ")), names));
    }

    private static void set(CmdContext c) throws Exception {
        Holder<Biome> biome = c.get("biome", BIOME);
        Region region;
        if (c.flag('p')) {
            if (!c.actor().isPlayer()) {
                c.print("kawe.setbiome.not-locatable");
                return;
            }
            BlockVec at = c.actor().block();
            region = new CuboidRegion(at, at);
        } else {
            region = c.selection();
        }
        Edit edit = c.edit();
        Ops.parallelChunks(edit, region, chunk -> {
            Set<Long> cells = new HashSet<>();
            region.forEachInChunk(ChunkKey.x(chunk), ChunkKey.z(chunk), (x, y, z) -> {
                long cell = BlockPos.asLong(x >> 2, y >> 2, z >> 2);
                if (!cells.contains(cell) && edit.setBiome(x, y, z, biome)) {
                    cells.add(cell);
                }
            });
        });
        Edit.Result result = c.commit(edit);
        c.print("kawe.setbiome.changed", result.biomes());
    }
}
