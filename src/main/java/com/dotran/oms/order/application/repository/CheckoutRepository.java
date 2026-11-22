package com.dotran.oms.order.application.repository;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Checkouts are customer-owned: every read and write filters by the customer (system rule 4), except the two job reads
 * marked system-wide, which scan due rows across customers by design.
 */
public interface CheckoutRepository {

    /** Inserts unless the key (cart id + version) exists; false when another row holds the key (nothing written). */
    boolean insertIfAbsent(Checkout checkout);

    Optional<Checkout> findByKey(CartId cartId, long cartVersion, CustomerId customerId);

    /** The checkout row locked FOR UPDATE (inside the caller's transaction). */
    Optional<Checkout> lockForUpdate(CheckoutId id, CustomerId customerId);

    /** PENDING → PLACED; false (nothing written) when the row is not PENDING or not this customer's. */
    boolean markPlaced(CheckoutId id, CustomerId customerId, Instant now);

    /** PENDING → REJECTED with its reasons; false when the row is not PENDING or not this customer's. */
    boolean markRejected(CheckoutId id, CustomerId customerId, List<RejectionReason> reasons, Instant now);

    /**
     * System-wide job read: the oldest PENDING checkout whose reserve_due_at has passed and whose id is not in
     * {@code excluded}, locked FOR UPDATE SKIP LOCKED.
     */
    Optional<Checkout> lockNextDueReserve(Instant now, Set<UUID> excluded);

    /** Moves reserve_due_at of a PENDING checkout to {@code until}; false when it is no longer PENDING. */
    boolean deferReserve(CheckoutId id, CustomerId customerId, Instant until, Instant now);

    /** System-wide job read: created_at of the oldest PENDING checkout (the oldest-PENDING gauge). */
    Optional<Instant> oldestPendingCreatedAt();
}
