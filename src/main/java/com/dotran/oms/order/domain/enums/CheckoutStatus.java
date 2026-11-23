package com.dotran.oms.order.domain.enums;

/** ADR 0007. EXPIRED is written by by-key/expire (not built yet). */
public enum CheckoutStatus {
    PENDING, PLACED, REJECTED, STALE, EXPIRED
}
