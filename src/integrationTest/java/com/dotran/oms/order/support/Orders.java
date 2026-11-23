package com.dotran.oms.order.support;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Test data for the order ITs: a PENDING checkout and PENDING orders under it. */
public final class Orders {

    public static final LocalDate DAY = LocalDate.of(2026, 11, 21);
    public static final DeliverySnapshot DELIVERY = new DeliverySnapshot("Nguyen Van A", "0901234567", "1 Le Loi",
            "Lau 2", "Ben Nghe", "1", "Ho Chi Minh", "700000", "VN");

    private Orders() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static Checkout pendingCheckout(UUID customer, Instant now) {
        return Checkout.pending(CustomerId.of(customer), CartId.of(UUID.randomUUID()), 3, "a".repeat(64),
                EventId.of(UUID.randomUUID()), now.minusSeconds(5), now, now.plusSeconds(150));
    }

    public static OrderLine line(String sku, String price, int qty) {
        return OrderLine.snapshot(UUID.randomUUID(), ProductId.of(UUID.randomUUID()), sku, "Product " + sku, "M", qty,
                new BigDecimal(price), "VND", 0);
    }

    public static Order pending(CheckoutId checkout, UUID customer, UUID merchant, UUID store, Instant now,
                                OrderLine... lines) {
        return Order.pending(checkout, CustomerId.of(customer), MerchantId.of(merchant), StoreId.of(store), "HCM01",
                DAY, "VND", UUID.randomUUID(), DELIVERY, List.of(lines), now);
    }
}
