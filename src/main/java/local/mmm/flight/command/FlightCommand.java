package local.mmm.flight.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.model.FlightProfile;
import local.mmm.flight.service.FlightService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

public final class FlightCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("balance", "add", "set", "clear", "reload");

    private final MMMFlightPlugin plugin;
    private final FlightService flightService;

    public FlightCommand(MMMFlightPlugin plugin, FlightService flightService) {
        this.plugin = plugin;
        this.flightService = flightService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return handleBalanceSelf(sender);
        }

        switch (args[0].toLowerCase()) {
            case "balance":
                return handleBalance(sender, args);
            case "add":
                return handleAdd(sender, args);
            case "set":
                return handleSet(sender, args);
            case "reload":
                return handleReload(sender);
            case "clear":
                return handleClear(sender, args);
            default:
                return handleBalanceSelf(sender);
        }
    }

    private boolean handleBalanceSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("player-only"));
            return true;
        }
        if (!player.hasPermission("mmmflight.use")) {
            player.sendMessage(plugin.message("no-permission"));
            return true;
        }
        sender.sendMessage(plugin.message("balance-self")
                .replace("%points%", String.valueOf(flightService.getPoints(player)))
                .replace("%max%", String.valueOf(flightService.getMaxPoints(player))));
        return true;
    }

    private boolean handleBalance(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return handleBalanceSelf(sender);
        }
        if (!sender.hasPermission("mmmflight.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        FlightProfile profile = flightService.resolveProfile(args[1]);
        if (profile == null) {
            sender.sendMessage(plugin.message("player-not-joined"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(profile.uuid());
        sender.sendMessage(plugin.message("balance-other")
                .replace("%player%", profile.name())
                .replace("%points%", String.valueOf(flightService.getPoints(profile.uuid())))
                .replace("%max%", String.valueOf(flightService.getMaxPoints(target))));
        return true;
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mmmflight.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        if (args.length < 3) {
            return false;
        }
        FlightProfile profile = flightService.resolveProfile(args[1]);
        if (profile == null) {
            sender.sendMessage(plugin.message("player-not-joined"));
            return true;
        }
        Integer amount = parseInt(args[2]);
        if (amount == null) {
            sender.sendMessage(plugin.message("invalid-number"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(profile.uuid());
        flightService.addPoints(profile.uuid(), amount, flightService.getMaxPoints(target));
        sender.sendMessage(plugin.message("added")
                .replace("%player%", profile.name())
                .replace("%amount%", String.valueOf(amount))
                .replace("%points%", String.valueOf(flightService.getPoints(profile.uuid()))));
        return true;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mmmflight.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        if (args.length < 3) {
            return false;
        }
        FlightProfile profile = flightService.resolveProfile(args[1]);
        if (profile == null) {
            sender.sendMessage(plugin.message("player-not-joined"));
            return true;
        }
        Integer amount = parseInt(args[2]);
        if (amount == null) {
            sender.sendMessage(plugin.message("invalid-number"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(profile.uuid());
        flightService.setPoints(profile.uuid(), amount, flightService.getMaxPoints(target));
        sender.sendMessage(plugin.message("set")
                .replace("%player%", profile.name())
                .replace("%points%", String.valueOf(flightService.getPoints(profile.uuid()))));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("mmmflight.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        plugin.reloadPlugin();
        sender.sendMessage(plugin.message("reloaded"));
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mmmflight.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            return false;
        }
        FlightProfile profile = flightService.resolveProfile(args[1]);
        if (profile == null) {
            sender.sendMessage(plugin.message("player-not-joined"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(profile.uuid());
        flightService.setPoints(profile.uuid(), 0, flightService.getMaxPoints(target));
        sender.sendMessage(plugin.message("cleared")
                .replace("%player%", profile.name()));
        return true;
    }

    private Integer parseInt(String input) {
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("mmmflight.admin")) {
                return copyPartialMatches(args[0], SUBS);
            }
            if (sender.hasPermission("mmmflight.use")) {
                return copyPartialMatches(args[0], List.of("balance"));
            }
            return Collections.emptyList();
        }
        if (!sender.hasPermission("mmmflight.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 2 && List.of("balance", "add", "set", "clear").contains(args[0].toLowerCase())) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return copyPartialMatches(args[1], names);
        }
        return Collections.emptyList();
    }

    private List<String> copyPartialMatches(String token, List<String> originals) {
        List<String> result = new ArrayList<>();
        StringUtil.copyPartialMatches(token, originals, result);
        return result;
    }
}
