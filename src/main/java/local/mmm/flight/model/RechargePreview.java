package local.mmm.flight.model;

public record RechargePreview(
        RechargeItem item,
        int points,
        int maxPoints,
        int percent,
        int reward,
        int afterPoints,
        int required,
        int available,
        int totalUsed,
        int totalLimit,
        int totalRemaining,
        int itemUsed,
        int itemLimit,
        int itemRemaining,
        RechargeStatus status,
        int shortage
) {

    public enum RechargeStatus {
        CAN_RECHARGE,
        DISABLED,
        UNKNOWN_ITEM,
        FULL,
        TOTAL_LIMIT,
        ITEM_LIMIT,
        NOT_ENOUGH,
        OFFLINE
    }

    public boolean canRecharge() {
        return status == RechargeStatus.CAN_RECHARGE;
    }
}
