package com.dotran.oms.order.domain.model;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;
import com.dotran.oms.order.domain.valueobject.RejectionReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One order per store of a checkout (BR-ORD-01, ADR 0007). Merchant-owned through {@code merchantId}; it also carries
 * the {@code customerId} of its checkout. {@code localDate} is the store's day at placement (D8): the daily cap and the
 * order number use it. The delivery fee stays null until delivery quotes exist (D2).
 */
public record Order(OrderId id, CheckoutId checkoutId, CustomerId customerId, MerchantId merchantId, StoreId storeId,
                    String storeCode, LocalDate localDate, Integer orderSeq, String orderNumber, OrderStatus status,
                    StockState stockState, List<RejectionReason> reserveRejection, Instant reserveExpiresAt,
                    Instant stockDueAt, int stockAttempts, Boolean releaseRestock, String releaseReason,
                    String currency, BigDecimal itemsTotal, UUID deliveryQuoteId, BigDecimal deliveryFeeAmount,
                    String deliveryFeeCurrency, DeliverySnapshot delivery, List<OrderLine> lines, Instant placedAt,
                    Instant confirmedAt, Instant createdAt, Instant updatedAt) {

    /**
     * The one order in which daily counters are touched, by every transaction (no lock cycle, ADR 0007): the store id's
     * UUID text, which sorts as Postgres sorts uuid; {@code UUID.compareTo} compares signed longs and can differ.
     */
    public static final Comparator<Order> BY_STORE_ID = Comparator.comparing(o -> o.storeId().getValue().toString());

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(checkoutId, "checkoutId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(storeCode, "storeCode");
        Objects.requireNonNull(localDate, "localDate");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(stockState, "stockState");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(itemsTotal, "itemsTotal");
        Objects.requireNonNull(deliveryQuoteId, "deliveryQuoteId");
        Objects.requireNonNull(delivery, "delivery");
        lines = List.copyOf(lines);
        reserveRejection = reserveRejection == null ? null : List.copyOf(reserveRejection);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        if (stockState.requiresDueTime() && stockDueAt == null) {
            throw new IllegalArgumentException(stockState + " needs a due time");
        }
    }

    /** A new order at placement: PENDING, ReserveStock sent; items total = sum of the line totals (BR-ORD-04). */
    public static Order pending(CheckoutId checkoutId, CustomerId customerId, MerchantId merchantId, StoreId storeId,
                                String storeCode, LocalDate localDate, String currency, UUID deliveryQuoteId,
                                DeliverySnapshot delivery, List<OrderLine> lines, Instant now) {
        BigDecimal total = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Order(OrderId.newOrderId(), checkoutId, customerId, merchantId, storeId, storeCode, localDate, null,
                null, OrderStatus.PENDING, StockState.RESERVE_SENT, null, null, null, 0, null, null, currency, total,
                deliveryQuoteId, null, null, delivery, lines, null, null, now, now);
    }
}
