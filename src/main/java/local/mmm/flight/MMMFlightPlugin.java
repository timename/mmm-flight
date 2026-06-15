package local.mmm.flight;

import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import local.mmm.flight.command.FlightCommand;
import local.mmm.flight.command.FlyCommand;
import local.mmm.flight.hook.LuckPermsHook;
import local.mmm.flight.listener.FlightListener;
import local.mmm.flight.model.RechargeItem;
import local.mmm.flight.placeholder.FlightPlaceholderExpansion;
import local.mmm.flight.service.FlightService;
import local.mmm.flight.storage.FlightStorage;
import local.mmm.flight.storage.MysqlFlightStorage;
import local.mmm.flight.storage.YamlFlightStorage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MMMFlightPlugin extends JavaPlugin {

    private FlightStorage storage;
    private FlightService flightService;
    private String serverId;
    private Set<String> allowedServers;
    private Set<String> allowedWorlds;
    private String limitPermissionPrefix;
    private List<Integer> limitPresets;
    private Set<String> registeredLimitPermissions;
    private int defaultMaxPoints;
    private int baseCostPerTick;
    private int chargeIntervalTicks;
    private int flightDeductGracePeriodTicks;
    private int flightDeductGracePeriodCooldownTicks;
    private String flightDeductActiveOnlyPermission;
    private boolean actionBarEnabled;
    private String actionBarFormat;
    private boolean disableFlightWhenEmpty;
    private boolean regenEnabled;
    private int regenAmountPerInterval;
    private int regenIntervalTicks;
    private boolean regenOnlyWhenNotFlying;
    private boolean regenOnlyInAllowedArea;
    private boolean rechargeEnabled;
    private ZoneId rechargeZoneId;
    private int rechargeDailyTotalLimit;
    private int rechargeDefaultPerItemLimit;
    private String rechargeLimitPermissionPrefix;
    private List<Integer> rechargeLimitPresets;
    private String rechargeIgnoreItemLimitPermission;
    private int rechargeIgnoreItemLimitFromTotalLimit;
    private RechargeItem.RewardMode rechargeDefaultRewardMode;
    private double rechargeDefaultRewardAmount;
    private double rechargeDefaultCostMultiplier;
    private List<Integer> rechargeDefaultCostTiers;
    private Map<String, RechargeItem> rechargeItems;
    private double defaultConsumeMultiplier;
    private Map<String, Double> serverConsumeMultipliers;
    private Map<String, Map<String, Double>> worldConsumeMultipliers;
    private boolean bossBarEnabled;
    private boolean bossBarShowWhenIdle;
    private String bossBarTitle;
    private boolean bossBarCostAnimationEnabled;
    private int bossBarCostAnimationIntervalTicks;
    private int bossBarCostHighlightDurationTicks;
    private String bossBarCostAnimationColorA;
    private String bossBarCostAnimationColorB;
    private BarColor bossBarColor;
    private BarStyle bossBarStyle;
    private Set<BarFlag> bossBarFlags;
    private FlightPlaceholderExpansion placeholderExpansion;
    private LuckPermsHook luckPermsHook;
    private FileConfiguration languageConfig;
    private BukkitTask yamlFlushTask;

    @Override
    public void onEnable() {
        registeredLimitPermissions = new HashSet<>();
        saveDefaultConfig();
        saveResource("lang/zh_CN.yml", false);
        reloadPlugin();
        registerCommands();
        Bukkit.getPluginManager().registerEvents(new FlightListener(this, flightService), this);
        flightService.start();
        registerPlaceholders();
        startYamlFlushTask();
        getLogger().info("MMMFlight enabled.");
    }

    @Override
    public void onDisable() {
        if (yamlFlushTask != null) {
            yamlFlushTask.cancel();
        }
        if (flightService != null) {
            flightService.shutdown();
        }
        if (storage != null) {
            storage.close();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        FileConfiguration config = getConfig();
        loadLanguage();

        serverId = config.getString("server-id", "default");
        allowedServers = new HashSet<>(config.getStringList("allowed.servers"));
        allowedWorlds = new HashSet<>(config.getStringList("allowed.worlds"));
        limitPermissionPrefix = config.getString("limits.permission-prefix", "mmmflight.limit.");
        limitPresets = parseLimitPresets(config.getIntegerList("limits.presets"));
        defaultMaxPoints = Math.max(0, config.getInt("flight.default-max-points", 1000));
        baseCostPerTick = Math.max(0, config.getInt("flight.base-cost-per-tick", 1));
        chargeIntervalTicks = Math.max(1, config.getInt("flight.charge-interval-ticks", 20));
        flightDeductGracePeriodTicks = Math.max(0, config.getInt("flight.deduct.grace-period-ticks", 20));
        flightDeductGracePeriodCooldownTicks = Math.max(0, config.getInt("flight.deduct.grace-period-cooldown-ticks", 100));
        flightDeductActiveOnlyPermission = config.getString("flight.deduct.active-flight-only-permission", "mmmflight.deduct.active-only");
        actionBarEnabled = config.getBoolean("actionbar.enabled", true);
        actionBarFormat = config.getString("actionbar.format", "&b飞行点数: &f%points%&7/&f%max%");
        disableFlightWhenEmpty = config.getBoolean("flight.disable-flight-when-empty", true);
        regenEnabled = config.getBoolean("regen.enabled", false);
        regenAmountPerInterval = Math.max(0, config.getInt("regen.amount-per-interval", 5));
        regenIntervalTicks = Math.max(1, config.getInt("regen.interval-ticks", 100));
        regenOnlyWhenNotFlying = config.getBoolean("regen.only-when-not-flying", true);
        regenOnlyInAllowedArea = config.getBoolean("regen.only-in-allowed-area", true);
        loadRechargeConfig(config);
        loadConsumeMultipliers(config);
        bossBarEnabled = config.getBoolean("bossbar.enabled", true);
        bossBarShowWhenIdle = config.getBoolean("bossbar.show-when-idle", false);
        bossBarTitle = config.getString("bossbar.title", "&b飞行能量: &f%points%&7/&f%max% %cost_display% &8(&f%percent%%&8)");
        bossBarCostAnimationEnabled = config.getBoolean("bossbar.cost-animation.enabled", true);
        bossBarCostAnimationIntervalTicks = Math.max(1, config.getInt("bossbar.cost-animation.blink-interval-ticks", 5));
        bossBarCostHighlightDurationTicks = Math.max(1, config.getInt("bossbar.cost-animation.highlight-duration-ticks", 12));
        bossBarCostAnimationColorA = config.getString("bossbar.cost-animation.color-a", "&c");
        bossBarCostAnimationColorB = config.getString("bossbar.cost-animation.color-b", "&e");
        bossBarColor = parseBarColor(config.getString("bossbar.color", "BLUE"));
        bossBarStyle = parseBarStyle(config.getString("bossbar.style", "SEGMENTED_10"));
        bossBarFlags = parseBarFlags(config.getStringList("bossbar.flags"));
        registerLimitPermissions();
        luckPermsHook = new LuckPermsHook(limitPermissionPrefix, defaultMaxPoints);

        FlightStorage oldStorage = storage;
        FlightStorage newStorage = createStorage();

        if (flightService == null) {
            storage = newStorage;
            flightService = new FlightService(this, storage, luckPermsHook);
        } else {
            flightService.reload(newStorage, luckPermsHook);
            storage = newStorage;
            if (oldStorage != null) {
                oldStorage.close();
            }
        }
        registerPlaceholders();
        startYamlFlushTask();
    }

    private void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        placeholderExpansion = new FlightPlaceholderExpansion(this, flightService);
        placeholderExpansion.register();
    }

    private FlightStorage createStorage() {
        String type = getConfig().getString("storage.type", "yaml");
        try {
            if ("mysql".equalsIgnoreCase(type)) {
                return new MysqlFlightStorage(this);
            }
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Failed to initialize MySQL storage, falling back to YAML.", exception);
        }
        return new YamlFlightStorage(this);
    }

    private void registerCommands() {
        PluginCommand mfly = getCommand("mfly");
        PluginCommand flight = getCommand("flight");
        if (mfly == null || flight == null) {
            throw new IllegalStateException("Commands not defined in plugin.yml");
        }
        FlyCommand flyCommand = new FlyCommand(this, flightService);
        FlightCommand flightCommand = new FlightCommand(this, flightService);
        mfly.setExecutor(flyCommand);
        mfly.setTabCompleter(flyCommand);
        flight.setExecutor(flightCommand);
        flight.setTabCompleter(flightCommand);
    }

    public String message(String path) {
        if (languageConfig == null) {
            return colorize(path);
        }
        return colorize(languageConfig.getString(path, path));
    }

    public List<String> messages(String path) {
        if (languageConfig == null) {
            return List.of(colorize(path));
        }
        List<String> values = languageConfig.getStringList(path);
        if (values.isEmpty()) {
            return List.of(message(path));
        }
        List<String> colored = new ArrayList<>();
        for (String value : values) {
            colored.add(colorize(value));
        }
        return colored;
    }

    public String colorize(String input) {
        if (input == null) {
            return "";
        }
        return input.replace('&', '\u00A7');
    }

    public String getServerId() {
        return serverId;
    }

    public boolean isAllowedServer(String currentServerId) {
        return allowedServers.isEmpty() || allowedServers.contains(currentServerId);
    }

    public boolean isAllowedWorld(String worldName) {
        return allowedWorlds.isEmpty() || allowedWorlds.contains(worldName);
    }

    public String getLimitPermissionPrefix() {
        return limitPermissionPrefix;
    }

    public int getDefaultMaxPoints() {
        return defaultMaxPoints;
    }

    public int getBaseCostPerTick() {
        return baseCostPerTick;
    }

    public int getChargeIntervalTicks() {
        return chargeIntervalTicks;
    }

    public int getFlightDeductGracePeriodTicks() {
        return flightDeductGracePeriodTicks;
    }

    public int getFlightDeductGracePeriodCooldownTicks() {
        return flightDeductGracePeriodCooldownTicks;
    }

    public String getFlightDeductActiveOnlyPermission() {
        return flightDeductActiveOnlyPermission;
    }

    public boolean isActionBarEnabled() {
        return actionBarEnabled;
    }

    public String getActionBarFormat() {
        return actionBarFormat;
    }

    public boolean isDisableFlightWhenEmpty() {
        return disableFlightWhenEmpty;
    }

    public boolean isRegenEnabled() {
        return regenEnabled;
    }

    public int getRegenAmountPerInterval() {
        return regenAmountPerInterval;
    }

    public int getRegenIntervalTicks() {
        return regenIntervalTicks;
    }

    public boolean isRegenOnlyWhenNotFlying() {
        return regenOnlyWhenNotFlying;
    }

    public boolean isRegenOnlyInAllowedArea() {
        return regenOnlyInAllowedArea;
    }

    public boolean isRechargeEnabled() {
        return rechargeEnabled;
    }

    public ZoneId getRechargeZoneId() {
        return rechargeZoneId;
    }

    public int getRechargeDailyTotalLimit() {
        return rechargeDailyTotalLimit;
    }

    public String getRechargeLimitPermissionPrefix() {
        return rechargeLimitPermissionPrefix;
    }

    public int getRechargeIgnoreItemLimitFromTotalLimit() {
        return rechargeIgnoreItemLimitFromTotalLimit;
    }

    public String getRechargeIgnoreItemLimitPermission() {
        return rechargeIgnoreItemLimitPermission;
    }

    public Map<String, RechargeItem> getRechargeItems() {
        return Collections.unmodifiableMap(rechargeItems);
    }

    public RechargeItem getRechargeItem(String key) {
        return rechargeItems.get(key);
    }

    public boolean isBossBarEnabled() {
        return bossBarEnabled;
    }

    public boolean isBossBarShowWhenIdle() {
        return bossBarShowWhenIdle;
    }

    public String getBossBarTitle() {
        return bossBarTitle;
    }

    public boolean isBossBarCostAnimationEnabled() {
        return bossBarCostAnimationEnabled;
    }

    public int getBossBarCostAnimationIntervalTicks() {
        return bossBarCostAnimationIntervalTicks;
    }

    public int getBossBarCostHighlightDurationTicks() {
        return bossBarCostHighlightDurationTicks;
    }

    public String getBossBarCostAnimationColorA() {
        return bossBarCostAnimationColorA;
    }

    public String getBossBarCostAnimationColorB() {
        return bossBarCostAnimationColorB;
    }

    public BarColor getBossBarColor() {
        return bossBarColor;
    }

    public BarStyle getBossBarStyle() {
        return bossBarStyle;
    }

    public Set<BarFlag> getBossBarFlags() {
        return bossBarFlags;
    }

    public LuckPermsHook getLuckPermsHook() {
        return luckPermsHook;
    }

    public int getEffectiveCost(Player player) {
        double multiplier = getConsumeMultiplier(player.getWorld().getName());
        return Math.max(0, (int) Math.round(baseCostPerTick * multiplier));
    }

    public double getConsumeMultiplier(String worldName) {
        Map<String, Double> worldMap = worldConsumeMultipliers.get(serverId);
        if (worldMap != null && worldMap.containsKey(worldName)) {
            return worldMap.get(worldName);
        }
        if (serverConsumeMultipliers.containsKey(serverId)) {
            return serverConsumeMultipliers.get(serverId);
        }
        return defaultConsumeMultiplier;
    }

    private BarColor parseBarColor(String input) {
        try {
            return BarColor.valueOf(input.toUpperCase());
        } catch (Exception ignored) {
            return BarColor.BLUE;
        }
    }

    private BarStyle parseBarStyle(String input) {
        try {
            return BarStyle.valueOf(input.toUpperCase());
        } catch (Exception ignored) {
            return BarStyle.SEGMENTED_10;
        }
    }

    private Set<BarFlag> parseBarFlags(List<String> inputs) {
        Set<BarFlag> flags = new HashSet<>();
        for (String input : inputs) {
            try {
                flags.add(BarFlag.valueOf(input.toUpperCase()));
            } catch (Exception ignored) {
            }
        }
        return flags;
    }

    private void loadLanguage() {
        String fileName = getConfig().getString("language.file", "zh_CN.yml");
        File langFolder = new File(getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        File langFile = new File(langFolder, fileName);
        if (!langFile.exists()) {
            if ("zh_CN.yml".equals(fileName)) {
                saveResource("lang/" + fileName, false);
            } else {
                saveResource("lang/zh_CN.yml", false);
            }
        }
        languageConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    private void startYamlFlushTask() {
        if (yamlFlushTask != null) {
            yamlFlushTask.cancel();
        }
        if (storage instanceof YamlFlightStorage yamlStorage) {
            yamlFlushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, yamlStorage::flush, 200L, 200L);
        }
    }

    private void loadConsumeMultipliers(FileConfiguration config) {
        defaultConsumeMultiplier = Math.max(0.0D, config.getDouble("consume-multiplier.default", 1.0D));
        serverConsumeMultipliers = new HashMap<>();
        worldConsumeMultipliers = new HashMap<>();

        ConfigurationSection serversSection = config.getConfigurationSection("consume-multiplier.servers");
        if (serversSection != null) {
            for (String key : serversSection.getKeys(false)) {
                serverConsumeMultipliers.put(key, Math.max(0.0D, serversSection.getDouble(key, 1.0D)));
            }
        }

        ConfigurationSection worldsSection = config.getConfigurationSection("consume-multiplier.worlds");
        if (worldsSection != null) {
            for (String serverKey : worldsSection.getKeys(false)) {
                ConfigurationSection worldSection = worldsSection.getConfigurationSection(serverKey);
                if (worldSection == null) {
                    continue;
                }
                Map<String, Double> worldMap = new HashMap<>();
                for (String worldKey : worldSection.getKeys(false)) {
                    worldMap.put(worldKey, Math.max(0.0D, worldSection.getDouble(worldKey, 1.0D)));
                }
                worldConsumeMultipliers.put(serverKey, worldMap);
            }
        }
    }

    private void loadRechargeConfig(FileConfiguration config) {
        rechargeEnabled = config.getBoolean("recharge.enabled", true);
        rechargeZoneId = parseZoneId(config.getString("recharge.timezone", "Asia/Shanghai"));
        rechargeDailyTotalLimit = Math.max(0, config.getInt("recharge.limits.default-daily-total", config.getInt("recharge.limits.daily-total", 16)));
        rechargeDefaultPerItemLimit = Math.max(0, config.getInt("recharge.limits.per-item-default", 4));
        rechargeLimitPermissionPrefix = config.getString("recharge.limits.permission-prefix", "mmmflight.recharge.limit.");
        rechargeLimitPresets = parseLimitPresets(config.getIntegerList("recharge.limits.presets"));
        if (rechargeLimitPresets.isEmpty()) {
            rechargeLimitPresets = List.of(16, 32, 48, 64);
        }
        rechargeIgnoreItemLimitPermission = config.getString("recharge.limits.ignore-item-limit-permission", "mmmflight.recharge.ignore-item-limit");
        rechargeIgnoreItemLimitFromTotalLimit = Math.max(0, config.getInt("recharge.limits.ignore-item-limit-from-total-limit", 32));
        rechargeDefaultRewardMode = parseRewardMode(config.getString("recharge.reward-default.mode", "percent"));
        rechargeDefaultRewardAmount = Math.max(0.0D, config.getDouble("recharge.reward-default.amount", 25.0D));
        rechargeDefaultCostMultiplier = Math.max(0.0D, config.getDouble("recharge.cost-default.multiplier", 2.0D));
        rechargeDefaultCostTiers = parsePositiveList(config.getIntegerList("recharge.cost-default.tiers"));
        if (rechargeDefaultCostTiers.isEmpty()) {
            rechargeDefaultCostTiers = List.of(16, 32, 64, 128);
        }
        rechargeItems = new LinkedHashMap<>();

        ConfigurationSection itemsSection = config.getConfigurationSection("recharge.items");
        if (itemsSection == null) {
            return;
        }
        for (String key : itemsSection.getKeys(false)) {
            ConfigurationSection section = itemsSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            Material material = parseMaterial(section.getString("material", key));
            if (material == null || material.isAir()) {
                getLogger().warning("Invalid recharge material for key: " + key);
                continue;
            }
            int baseCost = Math.max(1, section.getInt("base-cost", 32));
            String displayName = colorize(section.getString("display-name", key));
            double multiplier = Math.max(0.0D, section.getDouble("cost-multiplier", rechargeDefaultCostMultiplier));
            List<Integer> costTiers = parsePositiveList(section.getIntegerList("cost-tiers"));
            if (costTiers.isEmpty()) {
                costTiers = rechargeDefaultCostTiers;
            }
            int dailyLimit = Math.max(0, section.getInt("daily-limit", rechargeDefaultPerItemLimit));
            RechargeItem.RewardMode rewardMode = parseRewardMode(section.getString("reward.mode", rechargeDefaultRewardMode.name()));
            double rewardAmount = Math.max(0.0D, section.getDouble("reward.amount", rechargeDefaultRewardAmount));
            rechargeItems.put(key.toLowerCase(), new RechargeItem(key.toLowerCase(), displayName, material, baseCost, multiplier, costTiers, dailyLimit, rewardMode, rewardAmount));
        }
    }

    private ZoneId parseZoneId(String input) {
        try {
            return ZoneId.of(input);
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private Material parseMaterial(String input) {
        if (input == null) {
            return null;
        }
        try {
            return Material.valueOf(input.trim().toUpperCase());
        } catch (Exception ignored) {
            return null;
        }
    }

    private RechargeItem.RewardMode parseRewardMode(String input) {
        if ("fixed".equalsIgnoreCase(input)) {
            return RechargeItem.RewardMode.FIXED;
        }
        return RechargeItem.RewardMode.PERCENT;
    }

    private List<Integer> parseLimitPresets(List<Integer> configuredPresets) {
        return parsePositiveList(configuredPresets);
    }

    private List<Integer> parsePositiveList(List<Integer> configuredValues) {
        List<Integer> parsed = new ArrayList<>();
        for (Integer value : configuredValues) {
            if (value != null && value > 0) {
                parsed.add(value);
            }
        }
        return parsed;
    }

    private void registerLimitPermissions() {
        Set<String> currentPermissions = new HashSet<>();
        for (Integer preset : limitPresets) {
            currentPermissions.add(limitPermissionPrefix + preset);
        }
        for (Integer preset : rechargeLimitPresets) {
            currentPermissions.add(rechargeLimitPermissionPrefix + preset);
        }
        if (rechargeIgnoreItemLimitPermission != null && !rechargeIgnoreItemLimitPermission.isBlank()) {
            currentPermissions.add(rechargeIgnoreItemLimitPermission);
        }
        if (flightDeductActiveOnlyPermission != null && !flightDeductActiveOnlyPermission.isBlank()) {
            currentPermissions.add(flightDeductActiveOnlyPermission);
        }

        for (String oldPermission : new HashSet<>(registeredLimitPermissions)) {
            if (!currentPermissions.contains(oldPermission)) {
                Bukkit.getPluginManager().removePermission(oldPermission);
                registeredLimitPermissions.remove(oldPermission);
            }
        }

        for (String permissionNode : currentPermissions) {
            if (Bukkit.getPluginManager().getPermission(permissionNode) == null) {
                Bukkit.getPluginManager().addPermission(new Permission(permissionNode, PermissionDefault.FALSE));
            }
            registeredLimitPermissions.add(permissionNode);
        }
    }
}
