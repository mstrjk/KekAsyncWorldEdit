package teacommontea.kawe.tool;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import teacommontea.kawe.command.Cmd;
import teacommontea.kawe.command.Commands;
import teacommontea.kawe.math.BlockVec;
import teacommontea.kawe.session.Session;

public record ToolClick(Player player, Session session, BlockVec block, BlockFace face) {

    public void run(String label, Cmd.Handler handler) {
        Commands.runTool(player, label, handler);
    }
}
