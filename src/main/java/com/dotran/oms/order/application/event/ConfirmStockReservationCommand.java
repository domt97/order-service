package com.dotran.oms.order.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code ConfirmStockReservation} (oms-workspace contracts/events/ConfirmStockReservation.schema.json,
 * ADR 0006): sent when the order becomes PLACED; key = orderId. Re-sent with a new eventId until answered.
 */
public record ConfirmStockReservationCommand(UUID eventId, String eventType, Instant occurredAt, UUID merchantId,
                                             UUID storeId, UUID orderId) {

    /** Topic and outbox event_type; also read by oms-workspace scripts/extract_facts.py. */
    public static final String TYPE = "ConfirmStockReservation";
}
