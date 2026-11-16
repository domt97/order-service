package com.dotran.oms.order.common.id;

import com.dotran.oms.core.domain.id.BaseId;

import java.util.UUID;

/** One checkout of a cart version (BR-ORD-05). Kept in order-service until a second context needs it (workspace system rule 6). */
public class CheckoutId extends BaseId<UUID> {

    private CheckoutId(UUID value) {
        super(value);
    }

    public static CheckoutId of(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("CheckoutId must not be null");
        }
        return new CheckoutId(value);
    }

    public static CheckoutId newId() {
        return new CheckoutId(UUID.randomUUID());
    }
}
