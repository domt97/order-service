package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.infrastructure.message.dto.PlaceCheckoutMessage;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** PlaceCheckout message → command. Key fields are strict (DLT); every other field is lenient (null → validator). */
@UtilityClass
class PlaceCheckoutMapping {

    static final String TYPE = "PlaceCheckout";

    static PlaceCheckoutCmd toCmd(PlaceCheckoutMessage m) {
        if (!TYPE.equals(m.eventType())) {
            throw new InvalidPlaceCheckoutMessageException("eventType is not " + TYPE);
        }
        UUID eventId = required(m.eventId(), "eventId");
        UUID customerId = required(m.customerId(), "customerId");
        UUID cartId = required(m.cartId(), "cartId");
        Long cartVersion = wholeLong(m.cartVersion());
        if (cartVersion == null || cartVersion < 0) {
            throw new InvalidPlaceCheckoutMessageException("cartVersion missing, negative or not a whole number");
        }
        return PlaceCheckoutCmd.builder()
                .eventId(EventId.of(eventId))
                .occurredAt(instant(m.occurredAt()))
                .customerId(CustomerId.of(customerId))
                .cartId(CartId.of(cartId))
                .cartVersion(cartVersion)
                .requestedAt(instant(m.requestedAt()))
                .delivery(m.delivery() == null ? null : delivery(m.delivery()))
                .stores(m.stores() == null ? null : m.stores().stream()
                        .map(s -> s == null ? null : new PlaceCheckoutCmd.Store(uuid(s.merchantId()), uuid(s.storeId()),
                                uuid(s.quoteId()), s.lines() == null ? null : s.lines().stream()
                                .map(l -> l == null ? null : new PlaceCheckoutCmd.Line(uuid(l.lineId()),
                                        uuid(l.productId()), l.sku(), wholeInt(l.quantity())))
                                .toList()))
                        .toList())
                .build();
    }

    private static PlaceCheckoutCmd.Delivery delivery(PlaceCheckoutMessage.Delivery d) {
        PlaceCheckoutMessage.Address a = d.address();
        return new PlaceCheckoutCmd.Delivery(a == null ? null : new PlaceCheckoutCmd.Address(a.line1(), a.line2(),
                a.ward(), a.district(), a.city(), a.country(), a.postalCode()), d.recipientName(), d.phone());
    }

    private static UUID required(String value, String field) {
        UUID id = uuid(value);
        if (id == null) {
            throw new InvalidPlaceCheckoutMessageException(field + " missing or not a UUID");
        }
        return id;
    }

    static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            // UUID.fromString accepts short forms such as "1-2-3-4-5": keep only the canonical text.
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A whole number that fits a long, else null (3.7, 1e30 → null). */
    static Long wholeLong(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** A whole number that fits an int, else null: the validator answers INVALID_REQUEST. */
    static Integer wholeInt(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    static Instant instant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
