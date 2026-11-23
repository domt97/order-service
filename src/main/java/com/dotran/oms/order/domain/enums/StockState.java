package com.dotran.oms.order.domain.enums;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Where an order stands in the stock saga with inventory-service (ADR 0006, ADR 0007). Each inventory reply moves the
 * order out of exactly one state, so a late or repeated reply finds the order elsewhere and changes nothing.
 */
public enum StockState {
    RESERVE_SENT, RESERVED, RESERVE_REJECTED, CONFIRM_SENT, CONFIRMED, RELEASE_SENT, RELEASED;

    private static final Map<StockState, Set<StockState>> NEXT = Map.of(
            RESERVE_SENT, EnumSet.of(RESERVED, RESERVE_REJECTED, RELEASE_SENT),
            RESERVED, EnumSet.of(CONFIRM_SENT, RELEASE_SENT),
            RESERVE_REJECTED, EnumSet.noneOf(StockState.class),
            CONFIRM_SENT, EnumSet.of(CONFIRMED, RELEASE_SENT),
            CONFIRMED, EnumSet.noneOf(StockState.class),
            RELEASE_SENT, EnumSet.of(RELEASED),
            RELEASED, EnumSet.noneOf(StockState.class));

    public boolean canMoveTo(StockState next) {
        return NEXT.get(this).contains(next);
    }

    /** A command is out and waits for its reply: the re-send job picks the order up when its due time passes. */
    public boolean requiresDueTime() {
        return this == CONFIRM_SENT || this == RELEASE_SENT;
    }
}
