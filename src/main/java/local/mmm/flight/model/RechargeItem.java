package local.mmm.flight.model;

import org.bukkit.Material;

public record RechargeItem(
        String key,
        Material material,
        int baseCost,
        double costMultiplier,
        int dailyLimit,
        RewardMode rewardMode,
        double rewardAmount
) {

    public enum RewardMode {
        PERCENT,
        FIXED
    }
}
