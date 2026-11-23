package com.dotran.oms.order.domain.valueobject;

import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;

import java.util.List;
import java.util.Objects;

/** A checkout about to be placed: the PENDING checkout and one order per store, in {@link StoreOrder#BY_STORE_ID} order. */
public record PlacementPlan(Checkout checkout, List<StoreOrder> stores) {

    public PlacementPlan {
        Objects.requireNonNull(checkout, "checkout");
        if (checkout.status() != CheckoutStatus.PENDING) {
            throw new IllegalArgumentException("A placement starts from a PENDING checkout");
        }
        if (stores == null || stores.isEmpty()) {
            throw new IllegalArgumentException("A placement needs at least one store");
        }
        stores = stores.stream().sorted(StoreOrder.BY_STORE_ID).toList();
        for (StoreOrder s : stores) {
            if (!s.order().checkoutId().equals(checkout.id())) {
                throw new IllegalArgumentException("Order of another checkout");
            }
        }
    }

    public List<Order> orders() {
        return stores.stream().map(StoreOrder::order).toList();
    }
}
