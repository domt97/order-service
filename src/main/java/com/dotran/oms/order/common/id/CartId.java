package com.dotran.oms.order.common.id;

import com.dotran.oms.core.domain.id.BaseId;

import java.util.UUID;

/** A customer's cart (owned by cart-service); with its version, the key of a checkout. Kept in order-service until a second context needs it (workspace system rule 6). */
public class CartId extends BaseId<UUID> {

    private CartId(UUID value) {
        super(value);
    }

    public static CartId of(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("CartId must not be null");
        }
        return new CartId(value);
    }

    public static CartId newId() {
        return new CartId(UUID.randomUUID());
    }
}
