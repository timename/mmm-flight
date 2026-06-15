package local.mmm.flight.placeholder;

import local.mmm.flight.MMMFlightPlugin;
import local.mmm.flight.model.RechargePreview;
import local.mmm.flight.service.FlightService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class FlightPlaceholderExpansion extends PlaceholderExpansion {

    private final MMMFlightPlugin plugin;
    private final FlightService flightService;

    public FlightPlaceholderExpansion(MMMFlightPlugin plugin, FlightService flightService) {
        this.plugin = plugin;
        this.flightService = flightService;
    }

    @Override
    public String getIdentifier() {
        return "mmmflight";
    }

    @Override
    public String getAuthor() {
        return "Xiaomenxin";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null || player.getUniqueId() == null) {
            return "0";
        }
        String lower = params.toLowerCase();
        String basic = switch (lower) {
            case "points" -> String.valueOf(flightService.getPoints(player.getUniqueId()));
            case "max_points" -> String.valueOf(flightService.getMaxPoints(player));
            case "remaining_percent" -> String.valueOf(flightService.getPercent(player));
            case "server_id" -> plugin.getServerId();
            default -> null;
        };
        if (basic != null) {
            return basic;
        }
        return parseRechargePlaceholder(player, lower);
    }

    private String parseRechargePlaceholder(OfflinePlayer player, String params) {
        if ("recharge_total_used".equals(params)) {
            RechargePreview preview = flightService.previewRecharge(player, firstRechargeKey());
            return String.valueOf(preview.totalUsed());
        }
        if ("recharge_total_limit".equals(params)) {
            return String.valueOf(flightService.getRechargeDailyTotalLimit(player));
        }
        if ("recharge_total_remaining".equals(params)) {
            RechargePreview preview = flightService.previewRecharge(player, firstRechargeKey());
            return String.valueOf(preview.totalRemaining());
        }
        if ("recharge_can_ignore_item_limit".equals(params)) {
            return String.valueOf(flightService.canIgnoreItemLimit(player));
        }
        if ("recharge_ignore_item_limit_text".equals(params)) {
            return flightService.getIgnoreItemLimitText(flightService.canIgnoreItemLimit(player));
        }
        if ("recharge_limit_tier".equals(params)) {
            return String.valueOf(flightService.getRechargeDailyTotalLimit(player));
        }

        String prefix = "recharge_";
        if (!params.startsWith(prefix)) {
            return null;
        }
        String body = params.substring(prefix.length());
        String[] fields = {
                "name_",
                "material_",
                "used_",
                "limit_",
                "remaining_",
                "required_",
                "reward_",
                "available_",
                "can_",
                "status_",
                "item_limit_ignored_text_",
                "item_limit_ignored_",
                "normal_limit_",
                "max_required_"
        };
        for (String field : fields) {
            if (!body.startsWith(field)) {
                continue;
            }
            String key = body.substring(field.length());
            RechargePreview preview = flightService.previewRecharge(player, key);
            if (preview.item() == null) {
                return "";
            }
            return switch (field) {
                case "name_" -> preview.item().displayName();
                case "material_" -> preview.item().material().name();
                case "used_" -> String.valueOf(preview.itemUsed());
                case "limit_" -> String.valueOf(preview.itemLimit());
                case "remaining_" -> String.valueOf(preview.itemRemaining());
                case "required_" -> String.valueOf(preview.required());
                case "reward_" -> String.valueOf(Math.max(0, preview.afterPoints() - preview.points()));
                case "available_" -> String.valueOf(preview.available());
                case "can_" -> String.valueOf(preview.canRecharge());
                case "status_" -> flightService.getRechargeStatusText(preview);
                case "item_limit_ignored_text_" -> flightService.getItemLimitIgnoredText(preview.itemLimitIgnored());
                case "item_limit_ignored_" -> String.valueOf(preview.itemLimitIgnored());
                case "normal_limit_" -> String.valueOf(preview.itemLimit());
                case "max_required_" -> String.valueOf(preview.maxRequired());
                default -> null;
            };
        }
        return null;
    }

    private String firstRechargeKey() {
        return plugin.getRechargeItems().keySet().stream().findFirst().orElse("");
    }
}
