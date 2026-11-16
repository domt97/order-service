package com.dotran.oms.order.application.event;

import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code outbox_events}: topic = eventType, key = aggregateId (ADR 0002). Exactly one of customerId
 * (checkout replies) and merchantId (stock commands, later) is set (system rule 4, V1 CHECK).
 */
public record OutboxMessage(UUID eventId, UUID customerId, UUID merchantId, String aggregateType, UUID aggregateId,
                            String eventType, Object payload) {

    public OutboxMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(payload, "payload");
        if ((customerId == null) == (merchantId == null)) {
            throw new IllegalArgumentException("Exactly one of customerId and merchantId must be set");
        }
    }
}
