package com.dotran.oms.order.application.repository;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A guarded change of one order: from (status, stock state) to (status, stock state), scoped by order, merchant and
 * checkout. The optional fields are written only when set. Entering CONFIRM_SENT or RELEASE_SENT needs
 * {@code stockDueAt} and resets the attempts; entering any other stock state clears the due time. A status-only change
 * (stock state kept) keeps the stored due time and attempts and passes none.
 */
@Builder
public record OrderTransition(OrderId orderId, MerchantId merchantId, CheckoutId checkoutId,
                              OrderStatus fromStatus, StockState fromStock, OrderStatus toStatus, StockState toStock,
                              Instant now, Instant stockDueAt, Integer orderSeq, String orderNumber, Instant placedAt,
                              Instant confirmedAt, List<RejectionReason> reserveRejection, Instant reserveExpiresAt,
                              Boolean releaseRestock, String releaseReason, String actor, String reason) {

    public OrderTransition {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(checkoutId, "checkoutId");
        Objects.requireNonNull(fromStatus, "fromStatus");
        Objects.requireNonNull(fromStock, "fromStock");
        Objects.requireNonNull(toStatus, "toStatus");
        Objects.requireNonNull(toStock, "toStock");
        Objects.requireNonNull(now, "now");
        if (fromStock != toStock && !fromStock.canMoveTo(toStock)) {
            throw new IllegalArgumentException("Stock state " + fromStock + " cannot move to " + toStock);
        }
        if (fromStock == toStock) {
            // The stock state stays: its due time and attempts are kept as stored (a status-only change).
            if (stockDueAt != null) {
                throw new IllegalArgumentException("A status-only change keeps the stored due time; pass none");
            }
        } else if (toStock.requiresDueTime() != (stockDueAt != null)) {
            throw new IllegalArgumentException(toStock + (toStock.requiresDueTime() ? " needs" : " must not have")
                    + " a due time");
        }
        if (releaseReason != null && releaseReason.length() > 50) {
            throw new IllegalArgumentException("releaseReason longer than 50");
        }
        actor = actor == null ? "SYSTEM" : actor;
        reserveRejection = reserveRejection == null ? null : List.copyOf(reserveRejection);
    }
}
