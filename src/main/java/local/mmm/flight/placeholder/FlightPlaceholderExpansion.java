package local.mmm.flight.placeholder;

import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.service.FlightService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class FlightPlaceholderExpansion extends PlaceholderExpansion {

    private final MMMFlightPlugin plugin;
    private final FlightService flightService;

    public FlightPlaceholderExpansion(MMMFlightPlugin plugin, FlightService flightService) {
        this.plugin = plugin;
        this.flightService = flightService;
    }

    @Override
    public String getIdentifier() {
        return "mmmflight";
    }

    @Override
    public String getAuthor() {
        return "Xiaomenxin";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || player.getUniqueId() == null) {
            return "0";
        }
        return switch (params.toLowerCase()) {
            case "points" -> String.valueOf(flightService.getPoints(player.getUniqueId()));
            case "max_points" -> String.valueOf(flightService.getMaxPoints(player));
            case "remaining_percent" -> String.valueOf(flightService.getPercent(player));
            case "server_id" -> plugin.getServerId();
            default -> null;
        };
    }
}
