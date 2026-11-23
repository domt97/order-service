package com.dotran.oms.order.domain.valueobject;

import java.util.Objects;

/**
 * Delivery address, recipient and phone typed at checkout, copied onto each order (BR-ORD-15). Personal data: never
 * logged, never in an event or a command; {@link #toString()} is redacted.
 */
public record DeliverySnapshot(String recipientName, String phone, String line1, String line2, String ward,
                               String district, String city, String postalCode, String country) {

    public DeliverySnapshot {
        Objects.requireNonNull(recipientName, "recipientName");
        Objects.requireNonNull(phone, "phone");
        Objects.requireNonNull(line1, "line1");
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(country, "country");
    }

    @Override
    public String toString() {
        return "DeliverySnapshot[redacted]";
    }
}
