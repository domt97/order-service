package com.dotran.oms.order.domain.model;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.valueobject.RejectionReason;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The outcome stored under a checkout key (cart id + cart version, BR-ORD-05). Customer-owned (system rule 4). The
 * content hash decides whether a later command with the same key is the same checkout.
 */
public record Checkout(CheckoutId id, CustomerId customerId, CartId cartId, long cartVersion, CheckoutStatus status,
                       String contentHash, List<RejectionReason> reasons, EventId causationId, Instant requestedAt,
                       Instant createdAt) {

    public Checkout {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(cartId, "cartId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(causationId, "causationId");
        reasons = reasons == null ? null : List.copyOf(reasons);
        if (isRejectedOutcome(status) && (reasons == null || reasons.isEmpty())) {
            throw new IllegalArgumentException(status + " checkout needs at least one reason");
        }
    }

    /** A new rejected (REJECTED or STALE) outcome with a random id. */
    public static Checkout rejected(CheckoutStatus status, CustomerId customerId, CartId cartId, long cartVersion,
                                    String contentHash, List<RejectionReason> reasons, EventId causationId,
                                    Instant requestedAt, Instant now) {
        if (status != CheckoutStatus.REJECTED && status != CheckoutStatus.STALE) {
            throw new IllegalArgumentException("Not a rejected status: " + status);
        }
        return new Checkout(CheckoutId.newId(), customerId, cartId, cartVersion, status, contentHash,
                RejectionReason.normalise(reasons), causationId, requestedAt, now);
    }

    /** REJECTED, STALE and EXPIRED answer every copy of the command with their stored reasons. */
    public boolean answersWithRejection() {
        return isRejectedOutcome(status);
    }

    public boolean sameContent(String hash) {
        return contentHash.equals(hash);
    }

    private static boolean isRejectedOutcome(CheckoutStatus status) {
        return status == CheckoutStatus.REJECTED || status == CheckoutStatus.STALE || status == CheckoutStatus.EXPIRED;
    }
}
