package local.mmm.flight.storage;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.model.FlightAccount;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class YamlFlightStorage implements FlightStorage {

    private final File file;
    private final YamlConfiguration config;
    private volatile boolean dirty;

    public YamlFlightStorage(MMMFlightPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "points.yml");
        if (!file.exists()) {
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create points.yml", exception);
            }
        }
        this.config = YamlConfiguration.loadConfiguration(file);
        this.dirty = false;
    }

    @Override
    public synchronized FlightAccount loadAccount(UUID uuid) {
        String path = uuid.toString();
        if (config.isInt(path)) {
            return new FlightAccount(uuid, config.getInt(path, 0));
        }

        int points = Math.max(0, config.getInt(path + ".points", 0));
        LocalDate rechargeDate = parseDate(config.getString(path + ".recharge.date"));
        int dailyTotal = Math.max(0, config.getInt(path + ".recharge.total", 0));
        Map<String, Integer> counts = new HashMap<>();
        ConfigurationSection countsSection = config.getConfigurationSection(path + ".recharge.items");
        if (countsSection != null) {
            for (String key : countsSection.getKeys(false)) {
                int count = countsSection.getInt(key, 0);
                if (count > 0) {
                    counts.put(key, count);
                }
            }
        }
        return new FlightAccount(uuid, points, rechargeDate, dailyTotal, counts);
    }

    @Override
    public synchronized void saveAccount(FlightAccount account) {
        String path = account.getUuid().toString();
        config.set(path + ".points", account.getPoints());
        config.set(path + ".recharge.date", account.getRechargeDate() == null ? null : account.getRechargeDate().toString());
        config.set(path + ".recharge.total", account.getDailyRechargeTotal());
        config.set(path + ".recharge.items", null);
        for (Map.Entry<String, Integer> entry : account.getDailyRechargeCounts().entrySet()) {
            config.set(path + ".recharge.items." + entry.getKey(), entry.getValue());
        }
        dirty = true;
    }

    @Override
    public synchronized void close() {
        flush();
    }

    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        try {
            config.save(file);
            dirty = false;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save points.yml", exception);
        }
    }

    private LocalDate parseDate(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(input);
        } catch (Exception ignored) {
            return null;
        }
    }
}
