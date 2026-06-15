package local.mmm.flight.model;

import java.util.List;
import org.bukkit.Material;

public record RechargeItem(
        String key,
        String displayName,
        Material material,
        int baseCost,
        double costMultiplier,
        List<Integer> costTiers,
        int dailyLimit,
        RewardMode rewardMode,
        double rewardAmount
) {

    public enum RewardMode {
        PERCENT,
        FIXED
    }
}
