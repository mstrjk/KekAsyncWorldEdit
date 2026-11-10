package teacommontea.kawe.tool;

import net.kyori.adventure.text.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import teacommontea.kawe.clipboard.ClipboardHolder;
import teacommontea.kawe.command.CmdContext;
import teacommontea.kawe.command.CommandError;
import teacommontea.kawe.schematic.Schematics;

import java.io.IOException;
import java.nio.file.Path;

final class Scrolls {

    private Scrolls() {
    }

    static void apply(CmdContext c, BrushTool tool, BrushSettings settings, BrushSettings.Scroll action, int amount) throws Exception {
        switch (action) {
            case SIZE -> {
                double size = Math.max(0, settings.size() + amount);
                BrushTool.checkBrushRadius(size);
                settings.size(size);
                c.print("kawe.tool.size.set");
                c.print(Component.text("Size: " + size));
            }
            case RANGE -> {
                int range = Math.clamp((long) tool.range() + amount, 0, BrushTool.MAX_RANGE);
                tool.range(range);
                c.print("kawe.tool.range.set");
            }
            case TARGET_OFFSET -> {
                tool.targetOffset(tool.targetOffset() + amount);
                c.print("kawe.brush.brush.target.offset.set", tool.targetOffset());
            }
            case TARGET_MODE -> {
                BrushTool.TargetMode[] modes = BrushTool.TargetMode.values();
                BrushTool.TargetMode next = modes[Math.floorMod(tool.targetMode().ordinal() + amount, modes.length)];
                tool.targetMode(next);
                c.print("kawe.brush.brush.target.mode.set", next.name());
            }
            case MASK -> {
                String value = settings.nextScrollValue(amount);
                settings.mask(value);
                c.print("kawe.tool.mask.set");
                c.print(Component.text(value));
            }
            case PATTERN -> {
                String value = settings.nextScrollValue(amount);
                settings.material(value);
                c.print("kawe.tool.material.set");
                c.print(Component.text(value));
            }
            case CLIPBOARD -> {
                String value = settings.nextScrollValue(amount);
                Path file = Schematics.resolve(value, null);
                if (file == null) {
                    throw new CommandError("kawe.schematic.load.does-not-exist", value);
                }
                try {
                    c.session().clipboard(new ClipboardHolder(Schematics.load(file, null, MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME))));
                } catch (IOException e) {
                    throw new CommandError("kawe.schematic.schematic.load-failure", value);
                }
                c.print("kawe.schematic.schematic.loaded", value);
            }
            case NONE -> {
            }
        }
    }
}
