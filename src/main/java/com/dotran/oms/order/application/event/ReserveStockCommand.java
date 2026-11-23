package com.dotran.oms.order.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code ReserveStock} (oms-workspace contracts/events/ReserveStock.schema.json, ADR 0006). Sent through the
 * outbox to inventory-service, key = orderId; one per order, all lines or none (BR-INV-11). No prices, no personal data.
 */
public record ReserveStockCommand(UUID eventId, String eventType, Instant occurredAt, UUID merchantId, UUID storeId,
                                  UUID orderId, List<Line> lines) {

    /** Topic and outbox event_type; also read by oms-workspace scripts/extract_facts.py. */
    public static final String TYPE = "ReserveStock";

    public record Line(UUID orderItemId, UUID productId, String sku, int quantity) {
    }
}
