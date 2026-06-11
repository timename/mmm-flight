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
        return player.getEffectivePermissions().stream()
                .filter(info -> info.getValue() && info.getPermission().startsWith(permissionPrefix))
                .map(info -> info.getPermission().substring(permissionPrefix.length()))
                .filter(this::isNumeric)
                .map(Integer::parseInt)
                .max(Comparator.naturalOrder())
                .orElse(defaultMaxPoints);
    }

    public int resolveMaxPoints(OfflinePlayer player) {
        if (player.isOnline() && player.getPlayer() != null) {
            return resolveMaxPoints(player.getPlayer());
        }
        if (api == null || player.getUniqueId() == null) {
            return defaultMaxPoints;
        }

        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            user = api.getUserManager().loadUser(player.getUniqueId()).join();
        }
        if (user == null) {
            return defaultMaxPoints;
        }

        QueryOptions options = api.getContextManager().getStaticQueryOptions();
        CachedPermissionData permissionData = user.getCachedData().getPermissionData(options);
        return user.getNodes().stream()
                .map(node -> node.getKey())
                .filter(node -> permissionData.checkPermission(node).asBoolean())
                .filter(node -> node.startsWith(permissionPrefix))
                .map(node -> node.substring(permissionPrefix.length()))
                .filter(this::isNumeric)
                .map(Integer::parseInt)
                .max(Comparator.naturalOrder())
                .orElse(defaultMaxPoints);
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
