package local.mmm.flight.model;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FlightAccount {

    private final UUID uuid;
    private int points;
    private LocalDate rechargeDate;
    private int dailyRechargeTotal;
    private final Map<String, Integer> dailyRechargeCounts;

    public FlightAccount(UUID uuid, int points) {
        this(uuid, points, null, 0, new HashMap<>());
    }

    public FlightAccount(UUID uuid, int points, LocalDate rechargeDate, int dailyRechargeTotal, Map<String, Integer> dailyRechargeCounts) {
        this.uuid = uuid;
        this.points = Math.max(0, points);
        this.rechargeDate = rechargeDate;
        this.dailyRechargeTotal = Math.max(0, dailyRechargeTotal);
        this.dailyRechargeCounts = new HashMap<>();
        if (dailyRechargeCounts != null) {
            dailyRechargeCounts.forEach((key, value) -> {
                if (key != null && value != null && value > 0) {
                    this.dailyRechargeCounts.put(key, value);
                }
            });
        }
    }

    public UUID getUuid() {
        return uuid;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = Math.max(0, points);
    }

    public void addPoints(int amount) {
        setPoints(points + amount);
    }

    public LocalDate getRechargeDate() {
        return rechargeDate;
    }

    public void setRechargeDate(LocalDate rechargeDate) {
        this.rechargeDate = rechargeDate;
    }

    public int getDailyRechargeTotal() {
        return dailyRechargeTotal;
    }

    public void setDailyRechargeTotal(int dailyRechargeTotal) {
        this.dailyRechargeTotal = Math.max(0, dailyRechargeTotal);
    }

    public int getDailyRechargeCount(String key) {
        return dailyRechargeCounts.getOrDefault(key, 0);
    }

    public Map<String, Integer> getDailyRechargeCounts() {
        return new HashMap<>(dailyRechargeCounts);
    }

    public void incrementRechargeCount(String key) {
        dailyRechargeTotal++;
        dailyRechargeCounts.put(key, getDailyRechargeCount(key) + 1);
    }

    public void resetDailyRecharge(LocalDate date) {
        rechargeDate = date;
        dailyRechargeTotal = 0;
        dailyRechargeCounts.clear();
    }
}
