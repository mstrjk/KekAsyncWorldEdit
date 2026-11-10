package teacommontea.kawe.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import teacommontea.kawe.Sched;
import teacommontea.kawe.Settings;
import teacommontea.kawe.clipboard.Clipboard;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.clipboard.Transform;
import teacommontea.kawe.op.ClipboardOps;
import teacommontea.kawe.parse.InputParseException;
import teacommontea.kawe.schematic.Format;
import teacommontea.kawe.schematic.ImportReport;
import teacommontea.kawe.schematic.Schematics;
import teacommontea.kawe.text.Msg;
import teacommontea.kawe.text.PaginationBox;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Stream;

public final class SchematicCommands {

    private static final ArgType<Format> FORMAT = new ArgType<>() {
        @Override
        public Format parse(String raw, CmdContext context) throws InputParseException {
            Format format = Format.byAlias(raw);
            if (format == null) {
                throw InputParseException.of("kawe.schematic.unknown-format", raw);
            }
            return format;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            return Types.filter(Arrays.stream(Format.values()).flatMap(f -> f.aliases().stream()), partial);
        }
    };

    private static final ArgType<String> FILE = new ArgType<>() {
        @Override
        public String parse(String raw, CmdContext context) {
            return raw;
        }

        @Override
        public List<String> suggest(String partial, CmdContext context) {
            Path root = Schematics.root();
            if (root == null) {
                return List.of();
            }
            int slash = partial.lastIndexOf('/');
            String folder = slash < 0 ? "" : partial.substring(0, slash + 1);
            Path dir = root.resolve(folder).normalize();
            if (!Schematics.inside(dir) || !Files.isDirectory(dir)) {
                return List.of();
            }
            try (Stream<Path> list = Files.list(dir)) {
                return Types.filter(list.map(p -> folder + p.getFileName() + (Files.isDirectory(p) ? "/" : "")), partial).stream().limit(SUGGEST_LIMIT).toList();
            } catch (IOException e) {
                return List.of();
            }
        }
    };

    private static final int SUGGEST_LIMIT = 100;
    private static final int LIST_LIMIT = 5000;

    private SchematicCommands() {
    }

    public static void register() {
        Commands.add(Cmd.of("schematic").aliases("schem", "/schematic", "/schem").desc("Schematic commands for saving/loading areas")
                .sub(Cmd.of("loadall").perm("kawe.schematic.load").desc("Load multiple clipboards (paste will randomly choose one)")
                        .opt("formatName", FORMAT, "fast").arg("filename", FILE).flag('o', "overwrite").flag('r', "random rotation").flag('d', "dynamic")
                        .run(SchematicCommands::loadAll))
                .sub(Cmd.of("clear").perm("kawe.schematic.clear").desc("Clear your clipboard").immediate()
                        .run(c -> {
                            c.session().clipboard(null);
                            c.print("kawe.clipboard.clipboard.cleared");
                        }))
                .sub(Cmd.of("unload").perm("kawe.schematic.clear").desc("Remove a clipboard from your multi-clipboard").immediate()
                        .arg("filename", FILE).run(SchematicCommands::unload))
                .sub(Cmd.of("move").aliases("m").perm("kawe.schematic.move").desc("Move your loaded schematic")
                        .arg("directory", FILE).run(SchematicCommands::move))
                .sub(Cmd.of("load").perm("kawe.schematic.load").desc("Load a schematic into your clipboard")
                        .arg("filename", FILE).opt("formatName", Types.WORD, "").flag('r', "random rotation").flag('d', "dynamic")
                        .run(SchematicCommands::load))
                .sub(Cmd.of("save").perm("kawe.schematic.save").desc("Save your clipboard into a schematic file")
                        .arg("filename", FILE).opt("format", FORMAT, "fast").flag('f', "overwrite")
                        .run(SchematicCommands::save))
                .sub(Cmd.of("delete").aliases("d").confirm(Confirm.WILDCARD, "filename").perm("kawe.schematic.delete").desc("Delete a saved schematic")
                        .arg("filename", FILE).run(SchematicCommands::delete))
                .sub(Cmd.of("formats").aliases("listformats", "f").perm("kawe.schematic.formats").desc("List available formats").immediate()
                        .run(SchematicCommands::formats))
                .sub(Cmd.of("list").aliases("all", "ls").perm("kawe.schematic.list").desc("List saved schematics")
                        .opt("filter", Types.TEXT, "all").flag('p', "page", Types.INT).flag('d', "oldest").flag('n', "newest").flag('f', "format", Types.WORD)
                        .run(SchematicCommands::list)));
    }

    private static Registry<Biome> biomes() {
        return MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
    }

    private static String relative(Path file) {
        if (!Schematics.inside(file)) {
            throw new IllegalArgumentException("Path is outside the schematic folder: " + file);
        }
        return Schematics.root().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static Format extensionFormat(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= filename.lastIndexOf('/') ? null : Format.byExtension(filename.substring(dot + 1));
    }

    private static void loadFailed(CmdContext c, Path file, Exception e) {
        c.print("kawe.schematic.schematic.load-failure", e instanceof IOException && e.getMessage() != null ? e.getMessage() : file.getFileName().toString());
        Sched.plugin().getLogger().log(Level.WARNING, "Failed to load schematic " + file, e);
    }

    private static void load(CmdContext c) throws Exception {
        String filename = c.get("filename", FILE);
        String formatName = c.raw("formatName");
        Format format = null;
        if (formatName != null) {
            format = Format.byAlias(formatName);
            if (format == null) {
                c.print("kawe.schematic.unknown-format", formatName);
                return;
            }
        } else {
            format = extensionFormat(filename);
        }
        Path file = Schematics.resolve(filename, format);
        if (file == null) {
            c.print("kawe.schematic.load.does-not-exist", filename);
            return;
        }
        try {
            Clipboard clipboard = Schematics.load(file, formatName != null ? format : null, biomes());
            c.worked();
            ClipboardHolder holder = new ClipboardHolder(List.of(clipboard), List.of(relative(file)));
            applyRandom(holder, c.flag('r'), c.flag('d'));
            c.session().clipboard(holder);
            c.print("kawe.schematic.schematic.loaded", filename);
            warn(c, clipboard);
        } catch (IOException | RuntimeException e) {
            loadFailed(c, file, e);
        }
    }

    private static void warn(CmdContext c, Clipboard clipboard) {
        ImportReport report = clipboard.report();
        if (!report.lossy()) {
            return;
        }
        c.error("kawe.schematic.import.lossy", report.unknownBlocks(), report.fallbackMappings(), report.invalidBlockEntities(), report.invalidEntities());
        if (report.newerVersion()) {
            c.error("kawe.schematic.import.newer");
        }
        for (String detail : report.details()) {
            Sched.plugin().getLogger().warning("Import of " + c.actor().name() + ": " + detail);
        }
    }

    private static void applyRandom(ClipboardHolder holder, boolean random, boolean dynamic) {
        if (!random) {
            return;
        }
        if (dynamic) {
            holder.randomRotation(true);
        } else {
            holder.transform(Transform.IDENTITY.rotateY(ThreadLocalRandom.current().nextInt(4) * 90).combine(holder.base()));
        }
    }

    private static void loadAll(CmdContext c) throws Exception {
        Format format = c.get("formatName", FORMAT);
        boolean explicit = c.raw("formatName") != null;
        String filename = c.get("filename", FILE);
        Path target = Schematics.path(filename);
        List<Path> files = new ArrayList<>();
        Settings settings = Settings.get();
        if (target != null && Files.isDirectory(target)) {
            try (Stream<Path> list = Files.list(target)) {
                list.filter(Files::isRegularFile).filter(p -> Schematics.formatOf(p) != null).limit(settings.loadAllFiles() + 1L).forEach(files::add);
            }
            files.sort(null);
        } else {
            Path single = Schematics.resolve(filename, explicit ? format : null);
            if (single != null) {
                files.add(single);
            }
        }
        if (files.isEmpty()) {
            c.print("kawe.schematic.schematic.load-failure", filename);
            return;
        }
        if (files.size() > settings.loadAllFiles()) {
            throw new CommandError(Component.text("Too many schematics in " + filename + " (limit " + settings.loadAllFiles() + ")"));
        }
        long bytes = 0;
        for (Path file : files) {
            bytes += size(file);
        }
        if (bytes > settings.loadAllBytes()) {
            throw new CommandError(Component.text("The schematics in " + filename + " are too large to load together (" + bytes / 1000 + " of " + settings.loadAllBytes() / 1000 + " kb)"));
        }
        List<Clipboard> clipboards = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ClipboardHolder existing = c.session().clipboard();
        if (existing != null && !c.flag('o')) {
            clipboards.addAll(existing.clipboards());
            names.addAll(existing.names());
        }
        int before = clipboards.size();
        for (Path file : files) {
            if (names.contains(relative(file))) {
                continue;
            }
            try {
                Format found = Schematics.formatOf(file);
                Format chosen = explicit && found != null && found.extension().equals(format.extension()) ? format : null;
                Clipboard loaded = Schematics.load(file, chosen, biomes());
                warn(c, loaded);
                clipboards.add(loaded);
                names.add(relative(file));
            } catch (IOException | RuntimeException e) {
                loadFailed(c, file, e);
            }
        }
        if (clipboards.size() == before) {
            return;
        }
        ClipboardHolder holder = new ClipboardHolder(clipboards, names);
        applyRandom(holder, c.flag('r'), c.flag('d'));
        c.session().clipboard(holder);
        c.print("kawe.schematic.schematic.loaded", filename);
    }

    private static void unload(CmdContext c) throws Exception {
        String filename = c.get("filename", FILE);
        ClipboardHolder holder = c.session().clipboard();
        if (holder != null) {
            boolean exact = holder.names().contains(filename);
            if (!exact && holder.names().stream().filter(name -> stripExtension(name).equals(filename)).count() > 1) {
                throw new CommandError(Component.text("More than one loaded schematic is named " + filename + "; add the extension"));
            }
            List<Clipboard> kept = new ArrayList<>();
            List<String> names = new ArrayList<>();
            boolean removed = false;
            for (int i = 0; i < holder.clipboards().size(); i++) {
                String name = holder.names().get(i);
                if (exact ? name.equals(filename) : stripExtension(name).equals(filename)) {
                    removed = true;
                    continue;
                }
                kept.add(holder.clipboards().get(i));
                names.add(name);
            }
            if (removed) {
                if (kept.isEmpty()) {
                    c.session().clipboard(null);
                } else {
                    ClipboardHolder next = new ClipboardHolder(kept, names);
                    next.transform(holder.base());
                    next.randomRotation(holder.randomRotation());
                    c.session().clipboard(next);
                }
                c.print("kawe.clipboard.clipboard.cleared");
                return;
            }
        }
        c.print("kawe.clipboard.clipboard.uri.not.found", filename);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void move(CmdContext c) throws Exception {
        String directory = c.get("directory", FILE);
        Path dest = Schematics.path(directory);
        if (dest == null) {
            throw new CommandError("kawe.error.file-resolution.outside-root");
        }
        if (!Files.isDirectory(dest)) {
            c.print("kawe.schematic.directory-does-not-exist", directory);
            return;
        }
        ClipboardHolder holder = c.session().clipboard();
        if (holder == null || holder.names().stream().map(Schematics::path).noneMatch(file -> file != null && Files.isRegularFile(file))) {
            c.print("kawe.schematic.schematic.none");
            return;
        }
        List<String> names = new ArrayList<>(holder.names());
        try {
            for (int i = 0; i < names.size(); i++) {
                Path source = Schematics.path(names.get(i));
                if (source == null || !Files.isRegularFile(source)) {
                    continue;
                }
                Path target = dest.resolve(source.getFileName()).normalize();
                if (!Schematics.inside(target)) {
                    throw new CommandError("kawe.error.file-resolution.outside-root");
                }
                if (Files.exists(target)) {
                    c.print("kawe.schematic.schematic.move.exists", relative(target));
                    return;
                }
                try {
                    Schematics.move(source, target, false);
                } catch (IOException e) {
                    c.print("kawe.schematic.schematic.move.failed", relative(target), String.valueOf(e.getMessage()));
                    return;
                }
                String old = names.get(i);
                names.replaceAll(name -> name.equals(old) ? relative(target) : name);
                c.print("kawe.schematic.schematic.move.success", relative(source), relative(target));
            }
        } finally {
            if (!names.equals(holder.names()) && c.session().clipboard() == holder) {
                c.session().clipboard(holder.renamed(names));
            }
        }
    }

    private static void save(CmdContext c) throws Exception {
        String filename = c.get("filename", FILE);
        Format format = c.get("format", FORMAT);
        if (!format.writable()) {
            c.print("kawe.schematic.unknown-format", c.raw("format"));
            return;
        }
        String name = filename.toLowerCase(Locale.ROOT).endsWith("." + format.extension()) ? filename : filename + "." + format.extension();
        Path file = Schematics.path(name);
        if (file == null) {
            throw new CommandError("kawe.error.file-resolution.outside-root");
        }
        boolean overwrite = Files.exists(file);
        if (overwrite) {
            if (!c.actor().has("kawe.schematic.delete")) {
                throw new CommandError("kawe.schematic.already-exists");
            }
            if (!c.flag('f')) {
                c.print("kawe.schematic.save.already-exists");
                return;
            }
        }
        ClipboardHolder holder = c.clipboard();
        Clipboard target = ClipboardOps.transformed(holder.clipboard(), holder.base());
        try {
            Schematics.save(target, file, format, overwrite);
            c.worked();
        } catch (FileAlreadyExistsException e) {
            c.print("kawe.schematic.save.already-exists");
            return;
        } catch (IOException | IllegalArgumentException e) {
            c.error("kawe.schematic.failed-to-save");
            Sched.plugin().getLogger().log(Level.WARNING, "Failed to save schematic " + file, e);
            return;
        }
        if (overwrite) {
            c.print("kawe.schematic.schematic.overwritten", filename);
        } else {
            c.print("kawe.schematic.schematic.saved", filename);
        }
    }

    private static void delete(CmdContext c) throws Exception {
        String filename = c.get("filename", FILE);
        List<Path> files = new ArrayList<>();
        if (filename.equals("*")) {
            ClipboardHolder holder = c.session().clipboard();
            if (holder != null) {
                for (String name : holder.names()) {
                    Path file = Schematics.path(name);
                    if (file != null && !files.contains(file)) {
                        files.add(file);
                    }
                }
            }
        } else {
            Path file = Schematics.path(filename);
            if (file != null) {
                files.add(file);
            }
        }
        if (files.isEmpty()) {
            c.print("kawe.schematic.delete.does-not-exist", filename);
            return;
        }
        for (Path file : files) {
            String name = relative(file);
            if (!Files.isRegularFile(file)) {
                c.print("kawe.schematic.delete.does-not-exist", name);
                continue;
            }
            try {
                Files.delete(file);
                c.print("kawe.schematic.delete.deleted", name);
            } catch (IOException e) {
                c.print("kawe.schematic.delete.failed", name);
            }
        }
    }

    private static void formats(CmdContext c) {
        c.print("kawe.schematic.formats.title");
        for (Format format : Format.values()) {
            c.print(Component.text(format.name() + ": " + String.join(", ", format.aliases())));
        }
    }

    private static void list(CmdContext c) throws Exception {
        if (c.flag('d') && c.flag('n')) {
            throw new CommandError("kawe.schematic.sorting-old-new");
        }
        Integer pageFlag = c.flagValue('p', Types.INT);
        int page = pageFlag == null ? 1 : pageFlag;
        String formatName = c.flagRaw('f');
        Format format = formatName == null || formatName.isEmpty() ? null : Format.byAlias(formatName);
        if (format == null && formatName != null && !formatName.isEmpty()) {
            throw InputParseException.of("kawe.schematic.unknown-format", formatName);
        }
        String filter = c.get("filter", Types.TEXT);
        Path root = Schematics.root();
        Path dir = root;
        List<String> nameFilters = new ArrayList<>();
        for (String arg : filter.split(" ")) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.isEmpty() || lower.equals("all") || lower.equals("me") || lower.equals("mine") || lower.equals("local")
                    || lower.equals("private") || lower.equals("public") || lower.equals("global")) {
                continue;
            }
            if (arg.endsWith("/")) {
                Path candidate = Schematics.path(root.relativize(dir).resolve(arg).toString());
                if (candidate == null) {
                    throw new CommandError("kawe.error.file-resolution.outside-root");
                }
                dir = candidate;
            } else {
                nameFilters.add(lower);
            }
        }
        List<Path> files = new ArrayList<>();
        boolean partial = false;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> stream = Files.list(dir)) {
                Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    if (files.size() >= LIST_LIMIT) {
                        partial = true;
                        break;
                    }
                    Path p = it.next();
                    String lowerName = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (lowerName.startsWith(".") || !nameFilters.stream().allMatch(lowerName::contains)) {
                        continue;
                    }
                    Format found = Schematics.formatOf(p);
                    if (Files.isDirectory(p) || (found != null && (format == null || found.extension().equals(format.extension())))) {
                        files.add(p);
                    }
                }
            }
        }
        if (files.isEmpty()) {
            c.print("kawe.schematic.schematic.none");
            return;
        }
        Comparator<Path> order = Comparator.comparing((Path p) -> !Files.isDirectory(p));
        if (c.flag('d') || c.flag('n')) {
            Comparator<Path> byDate = Comparator.comparingLong(SchematicCommands::modified);
            order = order.thenComparing(c.flag('n') ? byDate.reversed() : byDate);
        } else {
            order = order.thenComparing(p -> p.getFileName().toString());
        }
        files.sort(order);
        ClipboardHolder holder = c.session().clipboard();
        List<String> loaded = holder == null ? List.of() : holder.names();
        List<Component> lines = new ArrayList<>();
        for (Path file : files) {
            String path = relative(file) + (Files.isDirectory(file) ? "/" : "");
            boolean directory = Files.isDirectory(file);
            List<ComponentLike> parts = new ArrayList<>();
            parts.add(Msg.tr("kawe.schematic.dash.symbol"));
            if (loaded.contains(relative(file))) {
                parts.add(Msg.tr("kawe.schematic.minus.symbol")
                        .clickEvent(ClickEvent.suggestCommand("//schematic unload " + path))
                        .hoverEvent(HoverEvent.showText(Msg.tr("kawe.schematic.unload"))));
            } else {
                parts.add(Msg.tr("kawe.schematic.plus.symbol")
                        .clickEvent(ClickEvent.suggestCommand("//schematic loadall " + path))
                        .hoverEvent(HoverEvent.showText(Msg.tr("kawe.schematic.clipboard"))));
            }
            if (!directory) {
                parts.add(Msg.tr("kawe.schematic.x.symbol")
                        .clickEvent(ClickEvent.suggestCommand("//schematic delete " + path))
                        .hoverEvent(HoverEvent.showText(Msg.tr("kawe.schematic.delete"))));
            }
            Component name = Component.text(file.getFileName().toString() + (directory ? "/" : ""));
            if (directory) {
                name = name.clickEvent(ClickEvent.suggestCommand("//schematic list " + path))
                        .hoverEvent(HoverEvent.showText(Msg.tr("kawe.schematic.list")));
            } else {
                name = name.clickEvent(ClickEvent.suggestCommand("//schematic load " + path))
                        .hoverEvent(HoverEvent.showText(Msg.tr("kawe.schematic.load")));
            }
            parts.add(name);
            if (!directory) {
                parts.add(Component.text(String.format(" (%.1f kb)", size(file) / 1000.0), NamedTextColor.GRAY));
            }
            lines.add(Component.textOfChildren(parts.toArray(ComponentLike[]::new)));
        }
        long total = 0;
        for (Path file : files) {
            if (!Files.isDirectory(file)) {
                total += size(file);
            }
        }
        String header = "| Schematics: " + String.format("%.1fkb", total / 1000.0) + (partial ? "+" : "") + " |";
        String pageCommand = "//schematic list " + filter + (c.flag('d') ? " -d" : "") + (c.flag('n') ? " -n" : "") + (formatName != null ? " -f " + formatName : "");
        try {
            c.print(new PaginationBox(header, pageCommand, lines).forConsole(!c.actor().isPlayer()).create(page));
        } catch (IllegalArgumentException e) {
            c.error("kawe.error.invalid-page");
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
