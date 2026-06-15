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
import local.mmm.flight.model.RechargePreview;
import local.mmm.flight.model.RechargePreview.RechargeStatus;
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
    private final java.util.Set<UUID> dirtyAccounts = ConcurrentHashMap.newKeySet();
    private final AtomicLong flightTickCounter = new AtomicLong();
    private final AtomicLong animationTickCounter = new AtomicLong();
    private FlightStorage storage;
    private LuckPermsHook luckPermsHook;
    private BukkitTask flightTask;
    private BukkitTask bossBarAnimationTask;
    private BukkitTask accountSaveTask;

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
        if (accountSaveTask != null) {
            accountSaveTask.cancel();
        }
        flightTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFlight, plugin.getChargeIntervalTicks(), plugin.getChargeIntervalTicks());
        bossBarAnimationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBossBarAnimation, 1L, 1L);
        accountSaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flushDirtyAccounts, 60L, 60L);
    }

    public void reload(FlightStorage newStorage, LuckPermsHook newLuckPermsHook) {
        flushDirtyAccounts();
        cache.values().forEach(storage::saveAccount);
        this.storage = newStorage;
        this.luckPermsHook = newLuckPermsHook;
        this.cache.clear();
        this.dirtyAccounts.clear();
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
        if (accountSaveTask != null) {
            accountSaveTask.cancel();
        }
        flushDirtyAccounts();
        cache.values().forEach(storage::saveAccount);
        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear();
        dirtyAccounts.clear();
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
            dirtyAccounts.remove(uuid);
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
        setPoints(uuid, points, maxPoints, true);
    }

    private void setPoints(UUID uuid, int points, int maxPoints, boolean saveImmediately) {
        int sanitized = Math.max(0, Math.min(points, Math.max(0, maxPoints)));
        FlightAccount account = getAccount(uuid);
        account.setPoints(sanitized);
        if (saveImmediately) {
            dirtyAccounts.remove(uuid);
            storage.saveAccount(account);
        } else {
            dirtyAccounts.add(uuid);
        }
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

    public void resetRecharge(UUID uuid) {
        FlightAccount account = getAccount(uuid);
        account.resetDailyRecharge(LocalDate.now(plugin.getRechargeZoneId()));
        dirtyAccounts.remove(uuid);
        storage.saveAccount(account);
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
        RechargePreview preview = previewRecharge(player, key);
        if (preview.status() == RechargeStatus.DISABLED) {
            player.sendMessage(plugin.message("recharge-disabled"));
            return false;
        }
        if (preview.status() == RechargeStatus.UNKNOWN_ITEM) {
            player.sendMessage(plugin.message("recharge-unknown")
                    .replace("%item%", key == null ? "" : key));
            return false;
        }
        if (preview.status() == RechargeStatus.FULL) {
            player.sendMessage(plugin.message("recharge-full")
                    .replace("%points%", String.valueOf(preview.points()))
                    .replace("%max%", String.valueOf(preview.maxPoints())));
            return false;
        }
        if (preview.status() == RechargeStatus.TOTAL_LIMIT) {
            player.sendMessage(plugin.message("recharge-total-limit")
                    .replace("%used%", String.valueOf(preview.totalUsed()))
                    .replace("%limit%", String.valueOf(preview.totalLimit())));
            return false;
        }
        if (preview.status() == RechargeStatus.ITEM_LIMIT) {
            player.sendMessage(plugin.message("recharge-item-limit")
                    .replace("%item%", preview.item().displayName())
                    .replace("%used%", String.valueOf(preview.itemUsed()))
                    .replace("%limit%", String.valueOf(preview.itemLimit())));
            return false;
        }
        if (preview.status() == RechargeStatus.NOT_ENOUGH) {
            player.sendMessage(plugin.message("recharge-not-enough")
                    .replace("%item%", preview.item().displayName())
                    .replace("%required%", String.valueOf(preview.required()))
                    .replace("%available%", String.valueOf(preview.available())));
            return false;
        }

        FlightAccount account = getAccount(player.getUniqueId());
        removeItems(player, preview.item(), preview.required());
        int actualReward = Math.max(0, preview.afterPoints() - preview.points());
        account.setPoints(preview.afterPoints());
        account.incrementRechargeCount(preview.item().key());
        resetRechargeIfNeeded(account);
        storage.saveAccount(account);
        syncFlightState(player);
        updateBossBar(player);
        player.sendMessage(plugin.message("recharge-success")
                .replace("%item%", preview.item().displayName())
                .replace("%cost%", String.valueOf(preview.required()))
                .replace("%amount%", String.valueOf(actualReward))
                .replace("%points%", String.valueOf(preview.afterPoints()))
                .replace("%max%", String.valueOf(preview.maxPoints()))
                .replace("%total_used%", String.valueOf(account.getDailyRechargeTotal()))
                .replace("%total_limit%", String.valueOf(preview.totalLimit()))
                .replace("%item_used%", String.valueOf(account.getDailyRechargeCount(preview.item().key())))
                .replace("%item_limit%", String.valueOf(preview.itemLimit()))
                .replace("%ignore_item_limit_text%", getIgnoreItemLimitText(preview.canIgnoreItemLimit()))
                .replace("%item_limit_ignored%", formatBoolean(preview.itemLimitIgnored()))
                .replace("%max_required%", String.valueOf(preview.maxRequired())));
        return true;
    }

    public String getRechargeSummary(Player player) {
        FlightAccount account = getAccount(player.getUniqueId());
        resetRechargeIfNeeded(account);
        return plugin.message("recharge-summary")
                .replace("%used%", String.valueOf(account.getDailyRechargeTotal()))
                .replace("%limit%", String.valueOf(getRechargeDailyTotalLimit(player)))
                .replace("%items%", plugin.getRechargeItems().values().stream()
                        .map(RechargeItem::displayName)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse(""));
    }

    public java.util.List<String> getRechargeInfo(Player player, String key) {
        RechargePreview preview = previewRecharge(player, key);
        if (preview.status() == RechargeStatus.UNKNOWN_ITEM) {
            return java.util.List.of(plugin.message("recharge-unknown").replace("%item%", key == null ? "" : key));
        }
        java.util.List<String> rendered = new java.util.ArrayList<>();
        for (String line : plugin.messages("recharge-info-detail")) {
            rendered.add(applyRechargePlaceholders(line, preview));
        }
        return rendered;
    }

    public RechargePreview previewRecharge(Player player, String key) {
        return previewRecharge(player, key, true);
    }

    public RechargePreview previewRecharge(OfflinePlayer offlinePlayer, String key) {
        Player online = offlinePlayer == null ? null : Bukkit.getPlayer(offlinePlayer.getUniqueId());
        return previewRecharge(online, key, online != null);
    }

    public String getRechargeStatusText(RechargePreview preview) {
        return switch (preview.status()) {
            case CAN_RECHARGE -> plugin.message("recharge-status-can");
            case CAN_RECHARGE_IGNORE_ITEM_LIMIT -> plugin.message("recharge-status-can-ignore-item-limit");
            case DISABLED -> plugin.message("recharge-status-disabled");
            case UNKNOWN_ITEM -> plugin.message("recharge-status-unknown");
            case FULL -> plugin.message("recharge-status-full");
            case TOTAL_LIMIT -> plugin.message("recharge-status-total-limit");
            case ITEM_LIMIT -> plugin.message("recharge-status-item-limit");
            case NOT_ENOUGH -> plugin.message("recharge-status-not-enough")
                    .replace("%shortage%", String.valueOf(preview.shortage()))
                    .replace("%item%", preview.item() == null ? "" : preview.item().displayName());
            case OFFLINE -> plugin.message("recharge-status-offline");
        };
    }

    private RechargePreview previewRecharge(Player player, String key, boolean includeInventory) {
        RechargeItem item = plugin.getRechargeItem(normalizeRechargeKey(key));
        if (item == null) {
            return new RechargePreview(null, 0, 0, 0, 0, 0, 0, 0, 0, plugin.getRechargeDailyTotalLimit(), 0, 0, 0, 0, false, false, 0, RechargeStatus.UNKNOWN_ITEM, 0);
        }
        if (!plugin.isRechargeEnabled()) {
            return buildPreview(player, item, includeInventory, RechargeStatus.DISABLED);
        }
        if (player == null) {
            return buildPreview(null, item, false, RechargeStatus.OFFLINE);
        }

        RechargePreview preview = buildPreview(player, item, includeInventory, RechargeStatus.CAN_RECHARGE);
        if (preview.points() >= preview.maxPoints()) {
            return buildPreview(player, item, includeInventory, RechargeStatus.FULL);
        }
        if (preview.totalLimit() > 0 && preview.totalUsed() >= preview.totalLimit()) {
            return buildPreview(player, item, includeInventory, RechargeStatus.TOTAL_LIMIT);
        }
        if (preview.itemLimit() > 0 && preview.itemUsed() >= preview.itemLimit() && !preview.canIgnoreItemLimit()) {
            return buildPreview(player, item, includeInventory, RechargeStatus.ITEM_LIMIT);
        }
        if (preview.available() < preview.required()) {
            return buildPreview(player, item, includeInventory, RechargeStatus.NOT_ENOUGH);
        }
        if (preview.itemLimitIgnored()) {
            return buildPreview(player, item, includeInventory, RechargeStatus.CAN_RECHARGE_IGNORE_ITEM_LIMIT);
        }
        return preview;
    }

    private RechargePreview buildPreview(Player player, RechargeItem item, boolean includeInventory, RechargeStatus status) {
        int points = 0;
        int maxPoints = 0;
        int percent = 0;
        int totalUsed = 0;
        int itemUsed = 0;
        if (player != null) {
            FlightAccount account = getAccount(player.getUniqueId());
            resetRechargeIfNeeded(account);
            points = account.getPoints();
            maxPoints = getMaxPoints(player);
            percent = getPercent(player);
            totalUsed = account.getDailyRechargeTotal();
            itemUsed = account.getDailyRechargeCount(item.key());
        }
        int totalLimit = player == null ? plugin.getRechargeDailyTotalLimit() : getRechargeDailyTotalLimit(player);
        int totalRemaining = totalLimit <= 0 ? 0 : Math.max(0, totalLimit - totalUsed);
        int itemLimit = item.dailyLimit();
        int itemRemaining = itemLimit <= 0 ? 0 : Math.max(0, itemLimit - itemUsed);
        boolean canIgnoreItemLimit = player != null && canIgnoreItemLimit(player, totalLimit);
        boolean itemLimitIgnored = canIgnoreItemLimit && itemLimit > 0 && itemUsed >= itemLimit;
        int required = getRequiredRechargeAmount(item, itemUsed, itemLimitIgnored);
        int maxRequired = getMaxRechargeAmount(item);
        int available = player != null && includeInventory ? countItems(player, item) : 0;
        int reward = calculateRechargeReward(item, maxPoints);
        int afterPoints = Math.min(maxPoints, points + reward);
        int shortage = Math.max(0, required - available);
        return new RechargePreview(item, points, maxPoints, percent, reward, afterPoints, required, available, totalUsed, totalLimit, totalRemaining, itemUsed, itemLimit, itemRemaining, canIgnoreItemLimit, itemLimitIgnored, maxRequired, status, shortage);
    }

    private String applyRechargePlaceholders(String line, RechargePreview preview) {
        RechargeItem item = preview.item();
        return line
                .replace("%item_name%", item.displayName())
                .replace("%item_key%", item.key())
                .replace("%material%", item.material().name())
                .replace("%points%", String.valueOf(preview.points()))
                .replace("%max%", String.valueOf(preview.maxPoints()))
                .replace("%percent%", String.valueOf(preview.percent()))
                .replace("%reward_mode%", getRewardModeText(item))
                .replace("%reward_value%", getRewardValueText(item))
                .replace("%reward%", String.valueOf(Math.max(0, preview.afterPoints() - preview.points())))
                .replace("%after_points%", String.valueOf(preview.afterPoints()))
                .replace("%required%", String.valueOf(preview.required()))
                .replace("%available%", String.valueOf(preview.available()))
                .replace("%cost_multiplier%", formatDouble(item.costMultiplier()))
                .replace("%base_cost%", String.valueOf(item.baseCost()))
                .replace("%total_used%", String.valueOf(preview.totalUsed()))
                .replace("%total_limit%", String.valueOf(preview.totalLimit()))
                .replace("%total_remaining%", String.valueOf(preview.totalRemaining()))
                .replace("%item_used%", String.valueOf(preview.itemUsed()))
                .replace("%item_limit%", String.valueOf(preview.itemLimit()))
                .replace("%item_remaining%", String.valueOf(preview.itemRemaining()))
                .replace("%can_ignore_item_limit%", formatBoolean(preview.canIgnoreItemLimit()))
                .replace("%ignore_item_limit_text%", getIgnoreItemLimitText(preview.canIgnoreItemLimit()))
                .replace("%item_limit_ignored%", formatBoolean(preview.itemLimitIgnored()))
                .replace("%max_required%", String.valueOf(preview.maxRequired()))
                .replace("%status%", getRechargeStatusText(preview));
    }

    public int getRechargeDailyTotalLimit(Player player) {
        return luckPermsHook.resolveMaxValue(player, plugin.getRechargeLimitPermissionPrefix(), plugin.getRechargeDailyTotalLimit());
    }

    public int getRechargeDailyTotalLimit(OfflinePlayer player) {
        return luckPermsHook.resolveMaxValue(player, plugin.getRechargeLimitPermissionPrefix(), plugin.getRechargeDailyTotalLimit());
    }

    public boolean canIgnoreItemLimit(Player player) {
        return canIgnoreItemLimit(player, getRechargeDailyTotalLimit(player));
    }

    public boolean canIgnoreItemLimit(OfflinePlayer player) {
        if (player.isOnline() && player.getPlayer() != null) {
            return canIgnoreItemLimit(player.getPlayer());
        }
        return getRechargeDailyTotalLimit(player) >= plugin.getRechargeIgnoreItemLimitFromTotalLimit();
    }

    public String getIgnoreItemLimitText(boolean canIgnore) {
        return plugin.message(canIgnore ? "recharge-ignore-item-limit-yes" : "recharge-ignore-item-limit-no");
    }

    private String getRewardModeText(RechargeItem item) {
        if (item.rewardMode() == RechargeItem.RewardMode.FIXED) {
            return plugin.message("recharge-reward-mode-fixed");
        }
        return plugin.message("recharge-reward-mode-percent");
    }

    private String getRewardValueText(RechargeItem item) {
        if (item.rewardMode() == RechargeItem.RewardMode.FIXED) {
            return formatDouble(item.rewardAmount()) + plugin.message("recharge-reward-unit-points");
        }
        return formatDouble(item.rewardAmount()) + "%";
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
            setPoints(player.getUniqueId(), current - effectiveCost, getMaxPoints(player), false);
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
        setPoints(player.getUniqueId(), current + plugin.getRegenAmountPerInterval(), max, false);
    }

    private void flushDirtyAccounts() {
        for (UUID uuid : new java.util.HashSet<>(dirtyAccounts)) {
            FlightAccount account = cache.get(uuid);
            if (account == null) {
                dirtyAccounts.remove(uuid);
                continue;
            }
            storage.saveAccount(account);
            dirtyAccounts.remove(uuid);
        }
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

    private boolean canIgnoreItemLimit(Player player, int totalLimit) {
        return player.hasPermission(plugin.getRechargeIgnoreItemLimitPermission())
                || totalLimit >= plugin.getRechargeIgnoreItemLimitFromTotalLimit();
    }

    private String formatBoolean(boolean value) {
        return String.valueOf(value);
    }

    private int getRequiredRechargeAmount(RechargeItem item, int usedItemCount, boolean itemLimitIgnored) {
        if (!item.costTiers().isEmpty()) {
            int index = itemLimitIgnored ? item.costTiers().size() - 1 : Math.min(Math.max(0, usedItemCount), item.costTiers().size() - 1);
            return Math.max(1, item.costTiers().get(index));
        }
        double amount = item.baseCost() * Math.pow(item.costMultiplier(), Math.max(0, usedItemCount));
        return Math.max(1, (int) Math.ceil(amount));
    }

    private int getMaxRechargeAmount(RechargeItem item) {
        if (!item.costTiers().isEmpty()) {
            return Math.max(1, item.costTiers().get(item.costTiers().size() - 1));
        }
        return getRequiredRechargeAmount(item, Math.max(0, item.dailyLimit() - 1), false);
    }

    private int calculateRechargeReward(RechargeItem item, int maxPoints) {
        if (item.rewardMode() == RechargeItem.RewardMode.FIXED) {
            return Math.max(0, (int) Math.ceil(item.rewardAmount()));
        }
        return Math.max(0, (int) Math.ceil(Math.max(0, maxPoints) * item.rewardAmount() / 100.0D));
    }

    private String formatDouble(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
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
