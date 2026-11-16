package com.dotran.oms.order.application.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code CheckoutRejected} (oms-workspace contracts/events/CheckoutRejected.schema.json). Published through
 * the outbox, key = cartId. {@code checkoutId} is absent for CONTENT_MISMATCH (no row stored for this content); a
 * reason's storeId and sku are absent when the reason is checkout- or store-level.
 */
public record CheckoutRejectedEvent(UUID eventId, String eventType, Instant occurredAt, UUID causationId,
                                    UUID customerId, UUID cartId, long cartVersion,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) UUID checkoutId,
                                    List<Reason> reasons) {

    /** Topic and outbox event_type; also read by oms-workspace scripts/extract_facts.py. */
    public static final String TYPE = "CheckoutRejected";
    public static final String AGGREGATE_TYPE = "Checkout";

    public record Reason(@JsonInclude(JsonInclude.Include.NON_NULL) UUID storeId,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String sku,
                         String code) {
    }
}
