package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.order.common.id.CheckoutId;

import java.util.List;
import java.util.Objects;

/**
 * A job step failed after it locked its rows; thrown from inside the {@code @Transactional} method so the step rolls
 * back, and names the rows so the job can skip and defer them. A failure that names no row (begin, commit, the scan
 * itself) is never this exception. The message holds ids only.
 */
public class StockSagaFailure extends RuntimeException {

    private final transient CheckoutRef checkout;
    private final transient List<OrderRef> orders;

    private StockSagaFailure(String message, CheckoutRef checkout, List<OrderRef> orders, Throwable cause) {
        super(message, cause);
        this.checkout = checkout;
        this.orders = List.copyOf(orders);
    }

    static StockSagaFailure checkout(CheckoutRef checkout, Throwable cause) {
        return new StockSagaFailure("Reserve timeout failed: checkoutId=" + checkout.id().getValue(), checkout,
                List.of(), cause);
    }

    static StockSagaFailure orders(List<OrderRef> orders, Throwable cause) {
        return new StockSagaFailure("Stock command re-send failed for " + orders.size() + " order(s)", null, orders,
                cause);
    }

    /** The checkout of a failed reserve-timeout step; null for a re-send failure. */
    public CheckoutRef checkoutRef() {
        return checkout;
    }

    /** The orders of a failed re-send (the whole locked batch, or the one order of a row retry); empty otherwise. */
    public List<OrderRef> orderRefs() {
        return orders;
    }

    public record CheckoutRef(CheckoutId id, CustomerId customerId) {
        public CheckoutRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(customerId, "customerId");
        }
    }

    public record OrderRef(OrderId id, MerchantId merchantId) {
        public OrderRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(merchantId, "merchantId");
        }
    }
}
