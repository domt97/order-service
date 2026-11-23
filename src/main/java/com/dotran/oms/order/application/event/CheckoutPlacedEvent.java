package com.dotran.oms.order.application.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code CheckoutPlaced} (oms-workspace contracts/events/CheckoutPlaced.schema.json). Published through the
 * outbox, key = cartId, when every order of the checkout is reserved and PLACED (BR-ORD-14); sent again, with a new
 * eventId, for a copy of the PlaceCheckout. No prices, no personal data.
 */
public record CheckoutPlacedEvent(UUID eventId, String eventType, Instant occurredAt, UUID causationId,
                                  UUID customerId, UUID cartId, long cartVersion, UUID checkoutId,
                                  List<PlacedOrder> orders) {

    /** Topic and outbox event_type; also read by oms-workspace scripts/extract_facts.py. */
    public static final String TYPE = "CheckoutPlaced";

    public record PlacedOrder(UUID orderId, UUID merchantId, UUID storeId, String orderNumber) {
    }
}
