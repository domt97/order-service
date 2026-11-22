package com.dotran.oms.order.domain.enums;

/**
 * Order statuses with third-party delivery, COD only (BR-ORD-06): the MVP never enters AWAITING_PAYMENT or EXPIRED, so
 * they are not listed. Placement writes PENDING, PLACED, REJECTED and CANCELLED; the rest come with acceptance and
 * delivery.
 */
public enum OrderStatus {
    PENDING, PLACED, ACCEPTED, PREPARING, READY, DELIVERING, DELIVERED, COMPLETED,
    REJECTED, CANCELLED, DELIVERY_FAILED
}
