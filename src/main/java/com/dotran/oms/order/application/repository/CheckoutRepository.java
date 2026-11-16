package com.dotran.oms.order.application.repository;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.domain.model.Checkout;

import java.util.Optional;

/** Checkouts are customer-owned: every read filters by the customer (system rule 4). */
public interface CheckoutRepository {

    /** Inserts unless the key (cart id + version) exists; false when another row holds the key (nothing written). */
    boolean insertIfAbsent(Checkout checkout);

    Optional<Checkout> findByKey(CartId cartId, long cartVersion, CustomerId customerId);
}
