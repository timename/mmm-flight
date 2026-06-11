package local.mmm.flight.storage;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import local.mmm.flight.MMMFlightPlugin;
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
    public synchronized int loadPoints(UUID uuid) {
        return Math.max(0, config.getInt(uuid.toString(), 0));
    }

    @Override
    public synchronized void savePoints(UUID uuid, int points) {
        config.set(uuid.toString(), Math.max(0, points));
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
}
