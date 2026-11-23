package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.valueobject.RejectionReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure rules of a PENDING checkout (BR-ORD-14, D3): wait until every order has a reserve reply (or the timeout), then
 * place all or reject all; and inventory's reasons in CheckoutRejected codes (CheckoutRejected.schema.json).
 */
public final class CheckoutDecision {

    private static final Map<String, RejectionCode> LINE_REASONS = Map.of(
            "INSUFFICIENT_STOCK", RejectionCode.INSUFFICIENT_STOCK,
            "NOT_ORDERABLE", RejectionCode.NOT_ORDERABLE,
            "UNKNOWN_SKU", RejectionCode.UNKNOWN_SKU);

    private CheckoutDecision() {
    }

    public enum Verdict {
        WAIT, FINALIZE, REJECT
    }

    /** {@code timedOut}: the reserve deadline passed, so an order still waiting for its reply counts as timed out. */
    public static Verdict decide(List<Order> orders, boolean timedOut) {
        boolean waiting = orders.stream().anyMatch(o -> o.stockState() == StockState.RESERVE_SENT);
        if (waiting && !timedOut) {
            return Verdict.WAIT;
        }
        return orders.stream().allMatch(o -> o.stockState() == StockState.RESERVED) ? Verdict.FINALIZE
                : Verdict.REJECT;
    }

    /**
     * Inventory's rejection of one order: line reasons pass through with store and SKU; STALE_COMMAND →
     * RESERVE_TIMEOUT, ORDER_CLOSED (or nothing usable) → RESERVE_FAILED, both store-level.
     */
    public static List<RejectionReason> reserveRejection(StockReplyCmd reply) {
        UUID store = reply.storeId().getValue();
        List<RejectionReason> reasons = new ArrayList<>();
        if ("STALE_COMMAND".equals(reply.orderReason())) {
            reasons.add(RejectionReason.store(RejectionCode.RESERVE_TIMEOUT, store));
        } else if (reply.orderReason() != null) {
            reasons.add(RejectionReason.store(RejectionCode.RESERVE_FAILED, store));
        }
        for (StockReplyCmd.Line line : reply.lines()) {
            RejectionCode code = LINE_REASONS.get(line.reason());
            reasons.add(code == null ? RejectionReason.store(RejectionCode.RESERVE_FAILED, store)
                    : RejectionReason.line(code, store, line.sku()));
        }
        if (reasons.isEmpty()) {
            reasons.add(RejectionReason.store(RejectionCode.RESERVE_FAILED, store));
        }
        return RejectionReason.normalise(reasons);
    }

    /** The checkout's reasons: each rejected order's stored reasons; each order still waiting at the deadline. */
    public static List<RejectionReason> checkoutReasons(List<Order> orders) {
        List<RejectionReason> reasons = new ArrayList<>();
        for (Order o : orders) {
            if (o.stockState() == StockState.RESERVE_REJECTED) {
                reasons.addAll(o.reserveRejection() == null || o.reserveRejection().isEmpty()
                        ? List.of(RejectionReason.store(RejectionCode.RESERVE_FAILED, o.storeId().getValue()))
                        : o.reserveRejection());
            } else if (o.stockState() == StockState.RESERVE_SENT) {
                reasons.add(RejectionReason.store(RejectionCode.RESERVE_TIMEOUT, o.storeId().getValue()));
            }
        }
        return RejectionReason.normalise(reasons);
    }
}
