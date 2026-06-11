package local.mmm.flight.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.hook.LuckPermsHook;
import local.mmm.flight.model.FlightProfile;
import local.mmm.flight.storage.FlightStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class FlightService {

    private final MMMFlightPlugin plugin;
    private final Map<UUID, Integer> cache = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> costHighlightUntilTick = new ConcurrentHashMap<>();
    private final AtomicLong flightTickCounter = new AtomicLong();
    private final AtomicLong animationTickCounter = new AtomicLong();
    private FlightStorage storage;
    private LuckPermsHook luckPermsHook;
    private BukkitTask flightTask;
    private BukkitTask bossBarAnimationTask;

    public FlightService(MMMFlightPlugin plugin, FlightStorage storage, LuckPermsHook luckPermsHook) {
        this.plugin = plugin;
        this.storage = storage;
        this.luckPermsHook = luckPermsHook;
    }

    public void start() {
        if (flightTask != null) {
            flightTask.cancel();
        }
        if (bossBarAnimationTask != null) {
            bossBarAnimationTask.cancel();
        }
        flightTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlight, plugin.getChargeIntervalTicks(), plugin.getChargeIntervalTicks());
        bossBarAnimationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBossBarAnimation, 1L, 1L);
    }

    public void reload(FlightStorage newStorage, LuckPermsHook newLuckPermsHook) {
        this.storage = newStorage;
        this.luckPermsHook = newLuckPermsHook;
        this.cache.clear();
        this.costHighlightUntilTick.clear();
        this.flightTickCounter.set(0L);
        this.animationTickCounter.set(0L);
        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear();
        start();
    }

    public void shutdown() {
        if (flightTask != null) {
            flightTask.cancel();
        }
        if (bossBarAnimationTask != null) {
            bossBarAnimationTask.cancel();
        }
        cache.forEach(storage::savePoints);
        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear();
        costHighlightUntilTick.clear();
        flightTickCounter.set(0L);
        animationTickCounter.set(0L);
        cache.clear();
    }

    public int getPoints(Player player) {
        return getPoints(player.getUniqueId());
    }

    public int getPoints(UUID uuid) {
        return cache.computeIfAbsent(uuid, storage::loadPoints);
    }

    public void reloadPoints(Player player) {
        UUID uuid = player.getUniqueId();
        int latest = storage.loadPoints(uuid);
        cache.put(uuid, latest);
        syncFlightState(player);
        updateBossBar(player);
    }

    public void unloadPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        Integer points = cache.remove(uuid);
        if (points != null) {
            storage.savePoints(uuid, points);
        }
        costHighlightUntilTick.remove(uuid);
        hideBossBar(player);
    }

    public void setPoints(Player player, int points) {
        setPoints(player.getUniqueId(), points, getMaxPoints(player));
        updateBossBar(player);
    }

    public void setPoints(UUID uuid, int points, int maxPoints) {
        int sanitized = Math.max(0, Math.min(points, Math.max(0, maxPoints)));
        cache.put(uuid, sanitized);
        storage.savePoints(uuid, sanitized);
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            syncFlightState(online);
            updateBossBar(online);
        }
    }

    public void addPoints(Player player, int amount) {
        setPoints(player, getPoints(player) + amount);
    }

    public void addPoints(UUID uuid, int amount, int maxPoints) {
        setPoints(uuid, getPoints(uuid) + amount, maxPoints);
    }

    public FlightProfile resolveProfile(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new FlightProfile(online.getUniqueId(), online.getName());
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(input);
        if (offline.getUniqueId() == null || (!offline.hasPlayedBefore() && !offline.isOnline())) {
            return null;
        }
        return new FlightProfile(offline.getUniqueId(), offline.getName() == null ? input : offline.getName());
    }

    public int getMaxPoints(Player player) {
        return luckPermsHook.resolveMaxPoints(player);
    }

    public int getMaxPoints(OfflinePlayer player) {
        return luckPermsHook.resolveMaxPoints(player);
    }

    public int getPercent(Player player) {
        int max = Math.max(1, getMaxPoints(player));
        return Math.max(0, Math.min(100, (int) Math.round((getPoints(player) * 100.0D) / max)));
    }

    public int getPercent(OfflinePlayer player) {
        int max = Math.max(1, getMaxPoints(player));
        int points = getPoints(player.getUniqueId());
        return Math.max(0, Math.min(100, (int) Math.round((points * 100.0D) / max)));
    }

    public boolean canUseFlight(Player player) {
        if (player.hasPermission("mmmflight.bypass.server") || player.hasPermission("mmmflight.bypass.world")) {
            return true;
        }
        boolean serverAllowed = plugin.isAllowedServer(plugin.getServerId()) || player.hasPermission("mmmflight.bypass.server");
        boolean worldAllowed = plugin.isAllowedWorld(player.getWorld().getName()) || player.hasPermission("mmmflight.bypass.world");
        return serverAllowed && worldAllowed;
    }

    public void syncFlightState(Player player) {
        if (!canUseFlight(player) && isManagedFlight(player)) {
            if (player.getAllowFlight() || player.isFlying()) {
                disableFlight(player, plugin.message("not-allowed-here"));
            }
            return;
        }
        if (getPoints(player) <= 0 && isManagedFlight(player) && plugin.isDisableFlightWhenEmpty()) {
            if (player.getAllowFlight() || player.isFlying()) {
                disableFlight(player, plugin.message("no-points"));
            }
        }
    }

    public boolean toggleFlight(Player player) {
        if (!canUseFlight(player)) {
            player.sendMessage(plugin.message("not-allowed-here"));
            return false;
        }
        if (player.getAllowFlight()) {
            disableFlight(player, plugin.message("flight-disabled"));
            return false;
        }
        if (getPoints(player) <= 0 && !player.hasPermission("mmmflight.bypass.consume")) {
            player.sendMessage(plugin.message("no-points"));
            return false;
        }
        player.setAllowFlight(true);
        updateBossBar(player);
        player.sendMessage(plugin.message("flight-enabled"));
        return true;
    }

    public void disableFlight(Player player, String message) {
        player.setFlying(false);
        player.setAllowFlight(false);
        costHighlightUntilTick.remove(player.getUniqueId());
        hideBossBar(player);
        if (message != null && !message.isEmpty()) {
            player.sendMessage(message);
        }
    }

    public boolean isManagedFlight(Player player) {
        return player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void tickFlight() {
        long currentTick = flightTickCounter.addAndGet(plugin.getChargeIntervalTicks());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isManagedFlight(player)) {
                continue;
            }
            syncFlightState(player);
            if (!player.isFlying() || player.hasPermission("mmmflight.bypass.consume")) {
                tryRegen(player, currentTick);
                sendActionBar(player);
                updateBossBar(player);
                continue;
            }

            int current = getPoints(player);
            int effectiveCost = plugin.getEffectiveCost(player);
            if (current <= 0) {
                if (plugin.isDisableFlightWhenEmpty()) {
                    disableFlight(player, plugin.message("no-points"));
                }
                continue;
            }

            costHighlightUntilTick.put(player.getUniqueId(), animationTickCounter.get() + plugin.getBossBarCostHighlightDurationTicks());
            setPoints(player, current - effectiveCost);
            if (getPoints(player) <= 0 && plugin.isDisableFlightWhenEmpty()) {
                disableFlight(player, plugin.message("no-points"));
            } else {
                sendActionBar(player);
                updateBossBar(player);
            }
        }
    }

    private void tickBossBarAnimation() {
        animationTickCounter.incrementAndGet();
        if (!plugin.isBossBarEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isFlying() && bossBars.containsKey(player.getUniqueId())) {
                updateBossBar(player);
            }
        }
    }

    private void tryRegen(Player player, long currentTick) {
        if (!plugin.isRegenEnabled()) {
            return;
        }
        if (plugin.isRegenOnlyWhenNotFlying() && player.isFlying()) {
            return;
        }
        if (plugin.isRegenOnlyInAllowedArea() && !canUseFlight(player)) {
            return;
        }
        int interval = plugin.getRegenIntervalTicks();
        if (interval <= 0 || currentTick % interval != 0) {
            return;
        }
        int current = getPoints(player);
        int max = getMaxPoints(player);
        if (current >= max) {
            return;
        }
        setPoints(player.getUniqueId(), current + plugin.getRegenAmountPerInterval(), max);
    }

    private void sendActionBar(Player player) {
        if (!plugin.isActionBarEnabled()) {
            return;
        }
        String text = plugin.colorize(plugin.getActionBarFormat())
                .replace("%points%", String.valueOf(getPoints(player)))
                .replace("%max%", String.valueOf(getMaxPoints(player)));
        player.sendActionBar(Component.text(text));
    }

    public void updateBossBar(Player player) {
        if (!plugin.isBossBarEnabled() || !isManagedFlight(player)) {
            hideBossBar(player);
            return;
        }
        boolean active = player.getAllowFlight() || player.isFlying();
        if (!active && !plugin.isBossBarShowWhenIdle()) {
            hideBossBar(player);
            return;
        }

        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), uuid -> {
            BossBar created = Bukkit.createBossBar("", plugin.getBossBarColor(), plugin.getBossBarStyle());
            for (BarFlag flag : plugin.getBossBarFlags()) {
                created.addFlag(flag);
            }
            created.addPlayer(player);
            return created;
        });
        if (!bar.getPlayers().contains(player)) {
            bar.addPlayer(player);
        }

        double progress = Math.max(0.0D, Math.min(1.0D, getPoints(player) / (double) Math.max(1, getMaxPoints(player))));
        int effectiveCost = plugin.getEffectiveCost(player);
        String costDisplay = buildCostDisplay(player, effectiveCost);
        String title = plugin.getBossBarTitle()
                .replace("%points%", String.valueOf(getPoints(player)))
                .replace("%max%", String.valueOf(getMaxPoints(player)))
                .replace("%cost%", String.valueOf(effectiveCost))
                .replace("%cost_display%", costDisplay)
                .replace("%percent%", String.valueOf(getPercent(player)));
        title = plugin.colorize(title);
        bar.setColor(plugin.getBossBarColor());
        bar.setStyle(plugin.getBossBarStyle());
        bar.setTitle(title);
        bar.setProgress(progress);
        bar.setVisible(true);
    }

    private String buildCostDisplay(Player player, int effectiveCost) {
        if (effectiveCost <= 0) {
            return plugin.getBossBarCostAnimationColorA() + "-0";
        }

        String color = plugin.getBossBarCostAnimationColorA();
        if (plugin.isBossBarCostAnimationEnabled() && isHighlightActive(player)) {
            long phase = animationTickCounter.get() / plugin.getBossBarCostAnimationIntervalTicks();
            color = phase % 2L == 0L ? plugin.getBossBarCostAnimationColorA() : plugin.getBossBarCostAnimationColorB();
        }
        return color + "-" + effectiveCost;
    }

    private boolean isHighlightActive(Player player) {
        Long untilTick = costHighlightUntilTick.get(player.getUniqueId());
        if (untilTick == null) {
            return false;
        }
        if (animationTickCounter.get() > untilTick) {
            costHighlightUntilTick.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    public void hideBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.removePlayer(player);
            bar.setVisible(false);
        }
    }
}
