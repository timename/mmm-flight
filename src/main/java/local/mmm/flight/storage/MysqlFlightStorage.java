package local.mmm.flight.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.model.FlightAccount;
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
                + "points INT NOT NULL,"
                + "recharge_date VARCHAR(10) NULL,"
                + "recharge_total INT NOT NULL DEFAULT 0,"
                + "recharge_items TEXT NULL"
                + ")";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            addColumnIfMissing(statement, "recharge_date", "VARCHAR(10) NULL");
            addColumnIfMissing(statement, "recharge_total", "INT NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "recharge_items", "TEXT NULL");
        }
    }

    @Override
    public synchronized FlightAccount loadAccount(UUID uuid) {
        String sql = "SELECT points, recharge_date, recharge_total, recharge_items FROM `" + table + "` WHERE uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new FlightAccount(
                            uuid,
                            resultSet.getInt("points"),
                            parseDate(resultSet.getString("recharge_date")),
                            resultSet.getInt("recharge_total"),
                            parseCounts(resultSet.getString("recharge_items"))
                    );
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load flight points", exception);
        }
        return new FlightAccount(uuid, 0);
    }

    @Override
    public synchronized void saveAccount(FlightAccount account) {
        String sql = "INSERT INTO `" + table + "` (uuid, points, recharge_date, recharge_total, recharge_items) VALUES (?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE points = VALUES(points), recharge_date = VALUES(recharge_date), "
                + "recharge_total = VALUES(recharge_total), recharge_items = VALUES(recharge_items)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, account.getUuid().toString());
            statement.setInt(2, account.getPoints());
            statement.setString(3, account.getRechargeDate() == null ? null : account.getRechargeDate().toString());
            statement.setInt(4, account.getDailyRechargeTotal());
            statement.setString(5, serializeCounts(account.getDailyRechargeCounts()));
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

    private void addColumnIfMissing(Statement statement, String column, String definition) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
        } catch (SQLException exception) {
            if (!"42S21".equals(exception.getSQLState()) && exception.getErrorCode() != 1060) {
                throw exception;
            }
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

    private Map<String, Integer> parseCounts(String input) {
        Map<String, Integer> result = new HashMap<>();
        if (input == null || input.isBlank()) {
            return result;
        }
        String[] entries = input.split(";");
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2 || parts[0].isBlank()) {
                continue;
            }
            try {
                int value = Integer.parseInt(parts[1]);
                if (value > 0) {
                    result.put(parts[0], value);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private String serializeCounts(Map<String, Integer> counts) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(';');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }
}
