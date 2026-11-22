package com.dotran.oms.order.application.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code ReleaseStockReservation} (oms-workspace contracts/events/ReleaseStockReservation.schema.json,
 * ADR 0006): ends the order's stock lifecycle; key = orderId. Re-sent with a new eventId, the same restock and reason,
 * until answered. Allowed before the ReserveStock (inventory keeps a closed order).
 */
public record ReleaseStockReservationCommand(UUID eventId, String eventType, Instant occurredAt, UUID merchantId,
                                             UUID storeId, UUID orderId, boolean restock, String reason) {

    /** Topic and outbox event_type; also read by oms-workspace scripts/extract_facts.py. */
    public static final String TYPE = "ReleaseStockReservation";
}
