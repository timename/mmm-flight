package local.mmm.flight.hook;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class ResidenceHook {

    private final Method getResidenceManagerMethod;
    private final Method getByLocMethod;
    private final Method getOwnerUuidMethod;
    private final Method getOwnerMethod;
    private final boolean available;

    public ResidenceHook() {
        Method managerMethod = null;
        Method byLocMethod = null;
        Method ownerUuidMethod = null;
        Method ownerMethod = null;
        boolean initialized = false;

        Plugin residencePlugin = Bukkit.getPluginManager().getPlugin("Residence");
        if (residencePlugin != null && residencePlugin.isEnabled()) {
            try {
                ClassLoader classLoader = residencePlugin.getClass().getClassLoader();
                Class<?> apiClass = Class.forName("com.bekvon.bukkit.residence.api.ResidenceApi", true, classLoader);
                Class<?> managerClass = Class.forName("com.bekvon.bukkit.residence.protection.ResidenceManager", true, classLoader);
                Class<?> residenceClass = Class.forName("com.bekvon.bukkit.residence.protection.ClaimedResidence", true, classLoader);

                managerMethod = apiClass.getMethod("getResidenceManager");
                byLocMethod = findByLocationMethod(managerClass);
                ownerUuidMethod = findNoArgMethod(residenceClass, "getOwnerUUID", "getOwnerUuid", "getOwnerUniqueId");
                ownerMethod = findNoArgMethod(residenceClass, "getOwner");
                initialized = byLocMethod != null;
            } catch (ReflectiveOperationException ignored) {
                initialized = false;
            }
        }

        this.getResidenceManagerMethod = managerMethod;
        this.getByLocMethod = byLocMethod;
        this.getOwnerUuidMethod = ownerUuidMethod;
        this.getOwnerMethod = ownerMethod;
        this.available = initialized;
    }

    public boolean isAvailable() {
        return available;
    }

    public ResidenceContext getResidenceContext(Player player) {
        if (!available) {
            return ResidenceContext.none();
        }
        try {
            Object manager = getResidenceManagerMethod.invoke(null);
            Object residence = invokeByLocation(manager, player.getLocation());
            if (residence == null) {
                return ResidenceContext.none();
            }
            UUID ownerUuid = resolveOwnerUuid(residence);
            boolean ownResidence = ownerUuid != null && ownerUuid.equals(player.getUniqueId());
            return new ResidenceContext(true, ownResidence);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return ResidenceContext.none();
        }
    }

    private Object invokeByLocation(Object manager, Location location) throws ReflectiveOperationException {
        int parameterCount = getByLocMethod.getParameterCount();
        if (parameterCount == 1) {
            return getByLocMethod.invoke(manager, location);
        }
        if (parameterCount == 2 && getByLocMethod.getParameterTypes()[1] == boolean.class) {
            return getByLocMethod.invoke(manager, location, true);
        }
        if (parameterCount == 3
            && getByLocMethod.getParameterTypes()[1] == boolean.class
            && getByLocMethod.getParameterTypes()[2] == boolean.class) {
            return getByLocMethod.invoke(manager, location, true, true);
        }
        return null;
    }

    private UUID resolveOwnerUuid(Object residence) throws ReflectiveOperationException {
        if (getOwnerUuidMethod != null) {
            Object value = getOwnerUuidMethod.invoke(residence);
            if (value instanceof UUID uuid) {
                return uuid;
            }
            if (value instanceof String text) {
                try {
                    return UUID.fromString(text);
                } catch (IllegalArgumentException ignored) {
                    return null;
                }
            }
        }
        if (getOwnerMethod != null) {
            Object value = getOwnerMethod.invoke(residence);
            if (value instanceof String ownerName) {
                return Bukkit.getOfflinePlayer(ownerName).getUniqueId();
            }
        }
        return null;
    }

    private static Method findByLocationMethod(Class<?> managerClass) {
        Method fallback = null;
        for (Method method : managerClass.getMethods()) {
            if (!"getByLoc".equals(method.getName()) || method.getParameterCount() < 1) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!parameterTypes[0].isAssignableFrom(Location.class)) {
                continue;
            }
            if (method.getParameterCount() == 1) {
                return method;
            }
            if (fallback == null && areSupportedExtraParameters(parameterTypes)) {
                fallback = method;
            }
        }
        return fallback;
    }

    private static boolean areSupportedExtraParameters(Class<?>[] parameterTypes) {
        for (int index = 1; index < parameterTypes.length; index++) {
            if (parameterTypes[index] != boolean.class) {
                return false;
            }
        }
        return parameterTypes.length <= 3;
    }

    private static Method findNoArgMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    public record ResidenceContext(boolean insideResidence, boolean ownResidence) {
        public static ResidenceContext none() {
            return new ResidenceContext(false, false);
        }
    }
}