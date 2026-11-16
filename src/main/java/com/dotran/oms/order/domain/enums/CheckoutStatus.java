package com.dotran.oms.order.domain.enums;

/** ADR 0007. Only REJECTED and STALE are written today; PENDING, PLACED (placement) and EXPIRED (by-key/expire) later. */
public enum CheckoutStatus {
    PENDING, PLACED, REJECTED, STALE, EXPIRED
}
