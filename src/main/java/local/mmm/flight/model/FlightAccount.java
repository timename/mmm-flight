package local.mmm.flight.model;

import java.util.UUID;

public final class FlightAccount {

    private final UUID uuid;
    private int points;

    public FlightAccount(UUID uuid, int points) {
        this.uuid = uuid;
        this.points = Math.max(0, points);
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
}
