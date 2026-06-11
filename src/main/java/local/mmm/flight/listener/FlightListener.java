package local.mmm.flight.listener;

import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.service.FlightService;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.PluginDisableEvent;

public final class FlightListener implements Listener {

    private final MMMFlightPlugin plugin;
    private final FlightService flightService;

    public FlightListener(MMMFlightPlugin plugin, FlightService flightService) {
        this.plugin = plugin;
        this.flightService = flightService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setAllowFlight(false);
        }
        flightService.reloadPoints(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        flightService.unloadPlayer(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        flightService.syncFlightState(event.getPlayer());
        flightService.updateBossBar(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        flightService.syncFlightState(event.getPlayer());
        flightService.updateBossBar(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            flightService.syncFlightState(event.getPlayer());
            flightService.updateBossBar(event.getPlayer());
        });
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin().equals(plugin)) {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                if (flightService.isManagedFlight(player)) {
                    player.setFlying(false);
                    player.setAllowFlight(false);
                }
            }
        }
    }
}
