package com.dotran.oms.order.domain.valueobject;

import com.dotran.oms.order.domain.model.Order;

import java.util.Comparator;
import java.util.Objects;

/**
 * One store's part of a placement: its new order and the store's daily cap from the quote (null = unlimited).
 */
public record StoreOrder(Order order, Integer maxOrdersPerDay) {

    /**
     * The one order in which counters are touched, by every transaction (no lock cycle, ADR 0007). Compares the UUID
     * text, which sorts as Postgres sorts uuid; {@code UUID.compareTo} compares signed longs and can differ.
     */
    public static final Comparator<StoreOrder> BY_STORE_ID = Comparator.comparing(StoreOrder::order, Order.BY_STORE_ID);

    public StoreOrder {
        Objects.requireNonNull(order, "order");
    }
}
