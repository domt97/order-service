package com.dotran.oms.order.application.repository;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Order;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orders and their lines. Merchant-owned: reads by id filter by merchant (and store), reads by checkout by customer
 * (system rule 4); the two re-send job reads are system-wide by design. Lines are read only with their order.
 * Writes run inside the caller's transaction.
 */
public interface OrderRepository {

    /** Inserts the orders, their lines and each order's first history row (→ PENDING). */
    void insertAll(List<Order> orders);

    /** The ids an inventory reply needs before it locks anything; empty for another merchant's or store's order. */
    Optional<OrderKey> findScoped(OrderId id, MerchantId merchantId, StoreId storeId);

    /** A checkout's orders with their lines, no lock, in id order. */
    List<Order> findByCheckout(CheckoutId checkoutId, CustomerId customerId);

    /** A checkout's orders with their lines, locked FOR UPDATE in id order. */
    List<Order> lockByCheckout(CheckoutId checkoutId, CustomerId customerId);

    /**
     * One guarded change of status and stock state: applies only while the order is still in {@code from}; false
     * (nothing written) otherwise. Writes a history row when the status changes.
     */
    boolean transition(OrderTransition transition);

    /**
     * System-wide job read: up to {@code limit} orders whose Confirm or Release is due, not in {@code excluded}, with
     * their lines, locked FOR UPDATE SKIP LOCKED.
     */
    List<Order> lockDueStockCommands(Instant now, int limit, Set<UUID> excluded);

    /** One order again for the row-by-row re-send: still CONFIRM_SENT/RELEASE_SENT and due, SKIP LOCKED; else empty. */
    Optional<Order> relockDue(OrderId id, MerchantId merchantId, Instant now);

    /**
     * After a re-send or a failed one: attempts + 1 and the next due time {@code now + min(initial × 2^attempts, max)},
     * only while the order is CONFIRM_SENT or RELEASE_SENT. Empty when it is not (nothing written).
     */
    Optional<Rescheduled> reschedule(OrderId id, MerchantId merchantId, Instant now, Duration initial, Duration max);

    record OrderKey(OrderId orderId, CheckoutId checkoutId, CustomerId customerId) {
    }

    record Rescheduled(int stockAttempts, StockState stockState) {
    }
}
