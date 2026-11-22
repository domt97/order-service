package com.dotran.oms.order.application.service.checkout;

import java.util.List;
import java.util.UUID;

/**
 * Thrown inside the placement transaction when one or more stores have no daily slot left (BR-STO-07). Unchecked, so
 * Spring rolls the whole placement back; the caller stores the checkout REJECTED with DAILY_CAP_REACHED per store.
 */
public class DailyCapReached extends RuntimeException {

    private final List<UUID> storeIds;

    public DailyCapReached(List<UUID> storeIds) {
        super("Daily cap reached for " + storeIds.size() + " store(s)");
        this.storeIds = List.copyOf(storeIds);
    }

    public List<UUID> storeIds() {
        return storeIds;
    }
}
