package local.mmm.flight.command;

import java.util.Collections;
import java.util.List;
import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.service.FlightService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class FlyCommand implements CommandExecutor, TabCompleter {

    private final MMMFlightPlugin plugin;
    private final FlightService flightService;

    public FlyCommand(MMMFlightPlugin plugin, FlightService flightService) {
        this.plugin = plugin;
        this.flightService = flightService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("player-only"));
            return true;
        }
        if (!player.hasPermission("mmmflight.use")) {
            player.sendMessage(plugin.message("no-permission"));
            return true;
        }
        flightService.toggleFlight(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
