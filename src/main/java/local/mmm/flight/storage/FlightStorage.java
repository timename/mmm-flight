package local.mmm.flight.storage;

import java.util.UUID;

public interface FlightStorage {

    int loadPoints(UUID uuid);

    void savePoints(UUID uuid, int points);

    default void deletePoints(UUID uuid) {
        savePoints(uuid, 0);
    }

    default void close() {
    }
}
