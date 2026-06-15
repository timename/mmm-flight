package local.mmm.flight.hook;

import java.util.Comparator;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public final class LuckPermsHook {

    private final String permissionPrefix;
    private final int defaultMaxPoints;
    private final LuckPerms api;

    public LuckPermsHook(String permissionPrefix, int defaultMaxPoints) {
        this.permissionPrefix = permissionPrefix;
        this.defaultMaxPoints = defaultMaxPoints;
        this.api = resolveApi();
    }

    public boolean isAvailable() {
        return api != null;
    }

    public int resolveMaxPoints(Player player) {
        return resolveMaxValue(player, permissionPrefix, defaultMaxPoints);
    }

    public int resolveMaxValue(Player player, String prefix, int defaultValue) {
        return player.getEffectivePermissions().stream()
                .filter(info -> info.getValue() && info.getPermission().startsWith(prefix))
                .map(info -> info.getPermission().substring(prefix.length()))
                .filter(this::isNumeric)
                .map(Integer::parseInt)
                .max(Comparator.naturalOrder())
                .orElse(defaultValue);
    }

    public int resolveMaxPoints(OfflinePlayer player) {
        return resolveMaxValue(player, permissionPrefix, defaultMaxPoints);
    }

    public int resolveMaxValue(OfflinePlayer player, String prefix, int defaultValue) {
        if (player.isOnline() && player.getPlayer() != null) {
            return resolveMaxValue(player.getPlayer(), prefix, defaultValue);
        }
        if (api == null || player.getUniqueId() == null) {
            return defaultValue;
        }

        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            user = api.getUserManager().loadUser(player.getUniqueId()).join();
        }
        if (user == null) {
            return defaultValue;
        }

        QueryOptions options = api.getContextManager().getStaticQueryOptions();
        CachedPermissionData permissionData = user.getCachedData().getPermissionData(options);
        return user.getNodes().stream()
                .map(node -> node.getKey())
                .filter(node -> permissionData.checkPermission(node).asBoolean())
                .filter(node -> node.startsWith(prefix))
                .map(node -> node.substring(prefix.length()))
                .filter(this::isNumeric)
                .map(Integer::parseInt)
                .max(Comparator.naturalOrder())
                .orElse(defaultValue);
    }

    private LuckPerms resolveApi() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return null;
        }
        try {
            return LuckPermsProvider.get();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private boolean isNumeric(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            if (!Character.isDigit(input.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
