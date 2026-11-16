package com.dotran.oms.order.application.command.checkout;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.common.id.CartId;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A PlaceCheckout command after the anti-corruption mapping. The key fields (eventId, customerId, cartId, cartVersion)
 * are always set: a record without them never becomes a command (it goes to the DLT). Every other field may be null or
 * wrong; {@code PlaceCheckoutValidator} answers that with INVALID_REQUEST. Delivery data is personal: never logged.
 */
@Builder
public record PlaceCheckoutCmd(EventId eventId, Instant occurredAt, CustomerId customerId, CartId cartId,
                               long cartVersion, Instant requestedAt, Delivery delivery, List<Store> stores) {

    @Builder
    public record Delivery(Address address, String recipientName, String phone) {
    }

    @Builder
    public record Address(String line1, String line2, String ward, String district, String city, String country,
                          String postalCode) {
    }

    @Builder
    public record Store(UUID merchantId, UUID storeId, UUID quoteId, List<Line> lines) {
    }

    @Builder
    public record Line(UUID lineId, UUID productId, String sku, Integer quantity) {
    }
}
