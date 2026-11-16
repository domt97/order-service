package com.dotran.oms.order.infrastructure.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The PlaceCheckout payload as sent by cart-service (anti-corruption layer; oms-workspace
 * contracts/events/PlaceCheckout.schema.json). Ids and instants are kept as text so a wrong value becomes
 * INVALID_REQUEST (or a DLT for the key fields) in {@code PlaceCheckoutMapping}, not a parse failure. Numbers are
 * {@code BigDecimal}: Jackson would turn 3.7 into an int 3 (a wrong checkout key or quantity). Personal data
 * (delivery): never logged, never in {@code toString} of a log line.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceCheckoutMessage(String eventId, String eventType, String occurredAt, String customerId, String cartId,
                                   BigDecimal cartVersion, String requestedAt, Delivery delivery, List<Store> stores) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delivery(Address address, String recipientName, String phone) {

        @Override
        public String toString() {
            return "Delivery[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(String line1, String line2, String ward, String district, String city, String country,
                          String postalCode) {

        @Override
        public String toString() {
            return "Address[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Store(String merchantId, String storeId, String quoteId, List<Line> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String lineId, String productId, String sku, BigDecimal quantity) {
    }
}
