package local.mmm.flight.storage;

import java.util.UUID;
import local.mmm.flight.model.FlightAccount;

public interface FlightStorage {

    FlightAccount loadAccount(UUID uuid);

    void saveAccount(FlightAccount account);

    default int loadPoints(UUID uuid) {
        return loadAccount(uuid).getPoints();
    }

    default void savePoints(UUID uuid, int points) {
        saveAccount(new FlightAccount(uuid, points));
    }

    default void deletePoints(UUID uuid) {
        savePoints(uuid, 0);
    }

    default void close() {
    }
}
