package com.dotran.oms.order.domain.enums;

/** The {@code CheckoutRejected} reason codes (oms-workspace contracts/events/CheckoutRejected.schema.json). */
public enum RejectionCode {
    STORE_INACTIVE,
    STORE_CLOSED,
    MERCHANT_SUSPENDED,
    DAILY_CAP_REACHED,
    PRODUCT_INACTIVE,
    UNKNOWN_SKU,
    CURRENCY_MISMATCH,
    INSUFFICIENT_STOCK,
    NOT_ORDERABLE,
    RESERVE_TIMEOUT,
    RESERVE_FAILED,
    DELIVERY_UNAVAILABLE,
    DELIVERY_FEE_CHANGED,
    STORE_UNAVAILABLE,
    STALE,
    EXPIRED,
    CONTENT_MISMATCH,
    INVALID_REQUEST
}
