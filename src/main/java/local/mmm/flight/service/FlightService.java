package local.mmm.flight.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.hook.LuckPermsHook;
import local.mmm.flight.model.FlightAccount;
import local.mmm.flight.model.FlightProfile;
import local.mmm.flight.model.RechargeItem;
import local.mmm.flight.storage.FlightStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

public final class FlightService {

    private final MMMFlightPlugin plugin;
    private final Map<UUID, FlightAccount> cache = new ConcurrentHashMap<>();
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
        cache.values().forEach(storage::saveAccount);
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
        cache.values().forEach(storage::saveAccount);
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
        return getAccount(uuid).getPoints();
    }

    private FlightAccount getAccount(UUID uuid) {
        return cache.computeIfAbsent(uuid, storage::loadAccount);
    }

    public void reloadPoints(Player player) {
        UUID uuid = player.getUniqueId();
        FlightAccount latest = storage.loadAccount(uuid);
        cache.put(uuid, latest);
        syncFlightState(player);
        updateBossBar(player);
    }

    public void unloadPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        FlightAccount account = cache.remove(uuid);
        if (account != null) {
            storage.saveAccount(account);
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
        FlightAccount account = getAccount(uuid);
        account.setPoints(sanitized);
        storage.saveAccount(account);
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

    public void removePoints(UUID uuid, int amount, int maxPoints) {
        setPoints(uuid, getPoints(uuid) - Math.max(0, amount), maxPoints);
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

    public boolean recharge(Player player, String key) {
        if (!plugin.isRechargeEnabled()) {
            player.sendMessage(plugin.message("recharge-disabled"));
            return false;
        }
        RechargeItem item = plugin.getRechargeItem(normalizeRechargeKey(key));
        if (item == null) {
            player.sendMessage(plugin.message("recharge-unknown")
                    .replace("%item%", key == null ? "" : key));
            return false;
        }

        FlightAccount account = getAccount(player.getUniqueId());
        resetRechargeIfNeeded(account);
        int maxPoints = getMaxPoints(player);
        int current = account.getPoints();
        if (current >= maxPoints) {
            player.sendMessage(plugin.message("recharge-full")
                    .replace("%points%", String.valueOf(current))
                    .replace("%max%", String.valueOf(maxPoints)));
            return false;
        }
        if (plugin.getRechargeDailyTotalLimit() > 0 && account.getDailyRechargeTotal() >= plugin.getRechargeDailyTotalLimit()) {
            player.sendMessage(plugin.message("recharge-total-limit")
                    .replace("%used%", String.valueOf(account.getDailyRechargeTotal()))
                    .replace("%limit%", String.valueOf(plugin.getRechargeDailyTotalLimit())));
            return false;
        }
        int usedItemCount = account.getDailyRechargeCount(item.key());
        if (item.dailyLimit() > 0 && usedItemCount >= item.dailyLimit()) {
            player.sendMessage(plugin.message("recharge-item-limit")
                    .replace("%item%", item.key())
                    .replace("%used%", String.valueOf(usedItemCount))
                    .replace("%limit%", String.valueOf(item.dailyLimit())));
            return false;
        }

        int required = getRequiredRechargeAmount(item, usedItemCount);
        int available = countItems(player, item);
        if (available < required) {
            player.sendMessage(plugin.message("recharge-not-enough")
                    .replace("%item%", item.key())
                    .replace("%required%", String.valueOf(required))
                    .replace("%available%", String.valueOf(available)));
            return false;
        }

        int reward = calculateRechargeReward(item, maxPoints);
        int newPoints = Math.min(maxPoints, current + reward);
        int actualReward = Math.max(0, newPoints - current);
        removeItems(player, item, required);
        account.setPoints(newPoints);
        account.incrementRechargeCount(item.key());
        storage.saveAccount(account);
        syncFlightState(player);
        updateBossBar(player);
        player.sendMessage(plugin.message("recharge-success")
                .replace("%item%", item.key())
                .replace("%cost%", String.valueOf(required))
                .replace("%amount%", String.valueOf(actualReward))
                .replace("%points%", String.valueOf(newPoints))
                .replace("%max%", String.valueOf(maxPoints))
                .replace("%total_used%", String.valueOf(account.getDailyRechargeTotal()))
                .replace("%total_limit%", String.valueOf(plugin.getRechargeDailyTotalLimit()))
                .replace("%item_used%", String.valueOf(account.getDailyRechargeCount(item.key())))
                .replace("%item_limit%", String.valueOf(item.dailyLimit())));
        return true;
    }

    public String getRechargeSummary(Player player) {
        FlightAccount account = getAccount(player.getUniqueId());
        resetRechargeIfNeeded(account);
        return plugin.message("recharge-summary")
                .replace("%used%", String.valueOf(account.getDailyRechargeTotal()))
                .replace("%limit%", String.valueOf(plugin.getRechargeDailyTotalLimit()))
                .replace("%items%", String.join(", ", plugin.getRechargeItems().keySet()));
    }

    public String getRechargeInfo(Player player, String key) {
        RechargeItem item = plugin.getRechargeItem(normalizeRechargeKey(key));
        if (item == null) {
            return plugin.message("recharge-unknown").replace("%item%", key == null ? "" : key);
        }
        FlightAccount account = getAccount(player.getUniqueId());
        resetRechargeIfNeeded(account);
        int usedItemCount = account.getDailyRechargeCount(item.key());
        return plugin.message("recharge-info")
                .replace("%item%", item.key())
                .replace("%used%", String.valueOf(usedItemCount))
                .replace("%limit%", String.valueOf(item.dailyLimit()))
                .replace("%total_used%", String.valueOf(account.getDailyRechargeTotal()))
                .replace("%total_limit%", String.valueOf(plugin.getRechargeDailyTotalLimit()))
                .replace("%required%", String.valueOf(getRequiredRechargeAmount(item, usedItemCount)))
                .replace("%reward%", String.valueOf(calculateRechargeReward(item, getMaxPoints(player))));
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

    private String normalizeRechargeKey(String key) {
        return key == null ? "" : key.toLowerCase();
    }

    private void resetRechargeIfNeeded(FlightAccount account) {
        LocalDate today = LocalDate.now(plugin.getRechargeZoneId());
        if (!today.equals(account.getRechargeDate())) {
            account.resetDailyRecharge(today);
            storage.saveAccount(account);
        }
    }

    private int getRequiredRechargeAmount(RechargeItem item, int usedItemCount) {
        double amount = item.baseCost() * Math.pow(item.costMultiplier(), Math.max(0, usedItemCount));
        return Math.max(1, (int) Math.ceil(amount));
    }

    private int calculateRechargeReward(RechargeItem item, int maxPoints) {
        if (item.rewardMode() == RechargeItem.RewardMode.FIXED) {
            return Math.max(0, (int) Math.ceil(item.rewardAmount()));
        }
        return Math.max(0, (int) Math.ceil(Math.max(0, maxPoints) * item.rewardAmount() / 100.0D));
    }

    private int countItems(Player player, RechargeItem item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == item.material()) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, RechargeItem item, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != item.material()) {
                continue;
            }
            int take = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                contents[slot] = null;
            }
        }
        player.getInventory().setContents(contents);
    }
}
