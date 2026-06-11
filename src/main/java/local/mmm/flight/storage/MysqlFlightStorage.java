package local.mmm.flight.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import local.mmm.flight.MMMFlightPlugin;
import org.bukkit.configuration.file.FileConfiguration;

public final class MysqlFlightStorage implements FlightStorage {

    private final String table;
    private final Connection connection;

    public MysqlFlightStorage(MMMFlightPlugin plugin) throws SQLException {
        FileConfiguration config = plugin.getConfig();
        this.table = sanitizeTableName(config.getString("storage.mysql.table", "mmm_flight_points"));

        String host = config.getString("storage.mysql.host", "127.0.0.1");
        int port = config.getInt("storage.mysql.port", 3306);
        String database = config.getString("storage.mysql.database", "minecraft");
        String username = config.getString("storage.mysql.username", "root");
        String password = config.getString("storage.mysql.password", "");
        boolean useSsl = config.getBoolean("storage.mysql.use-ssl", false);

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl + "&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        connection = DriverManager.getConnection(url, properties);
        createTableIfNeeded();
    }

    private void createTableIfNeeded() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS `" + table + "` ("
                + "uuid VARCHAR(36) PRIMARY KEY,"
                + "points INT NOT NULL"
                + ")";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    @Override
    public synchronized int loadPoints(UUID uuid) {
        String sql = "SELECT points FROM `" + table + "` WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Math.max(0, resultSet.getInt("points"));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load flight points", exception);
        }
        return 0;
    }

    @Override
    public synchronized void savePoints(UUID uuid, int points) {
        String sql = "INSERT INTO `" + table + "` (uuid, points) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE points = VALUES(points)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            statement.setInt(2, Math.max(0, points));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to save flight points", exception);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private String sanitizeTableName(String input) {
        String candidate = input == null ? "mmm_flight_points" : input.trim();
        if (!candidate.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid MySQL table name in config.");
        }
        return candidate;
    }
}
