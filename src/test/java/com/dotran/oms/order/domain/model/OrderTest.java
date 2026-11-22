package com.dotran.oms.order.domain.model;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private static final DeliverySnapshot DELIVERY = new DeliverySnapshot("Nguyen Van A", "0901234567", "1 Le Loi",
            null, "Ben Nghe", "1", "Ho Chi Minh", null, "VN");

    private static OrderLine line(String price, int qty) {
        return OrderLine.snapshot(UUID.randomUUID(), ProductId.of(UUID.randomUUID()), "SKU-" + price, "P", null, qty,
                new BigDecimal(price), "VND", 0);
    }

    @Test
    @DisplayName("pending: PENDING / RESERVE_SENT, no number, no fee, items total = sum of line totals")
    void pending() {
        Order o = Order.pending(CheckoutId.newId(), CustomerId.of(UUID.randomUUID()), MerchantId.of(UUID.randomUUID()),
                StoreId.of(UUID.randomUUID()), "HCM01", LocalDate.of(2026, 11, 21), "VND", UUID.randomUUID(), DELIVERY,
                List.of(line("10000", 2), line("5000", 1)), Instant.EPOCH);
        assertThat(o.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(o.stockState()).isEqualTo(StockState.RESERVE_SENT);
        assertThat(o.orderNumber()).isNull();
        assertThat(o.deliveryFeeAmount()).isNull();
        assertThat(o.stockDueAt()).isNull();
        assertThat(o.itemsTotal()).isEqualByComparingTo("25000");
    }

    @Test
    @DisplayName("an order needs a line; delivery data never appears in toString (BR-ORD-15)")
    void invariants() {
        assertThatThrownBy(() -> Order.pending(CheckoutId.newId(), CustomerId.of(UUID.randomUUID()),
                MerchantId.of(UUID.randomUUID()), StoreId.of(UUID.randomUUID()), "HCM01", LocalDate.of(2026, 11, 21),
                "VND", UUID.randomUUID(), DELIVERY, List.of(), Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        Order o = Order.pending(CheckoutId.newId(), CustomerId.of(UUID.randomUUID()), MerchantId.of(UUID.randomUUID()),
                StoreId.of(UUID.randomUUID()), "HCM01", LocalDate.of(2026, 11, 21), "VND", UUID.randomUUID(), DELIVERY,
                List.of(line("1", 1)), Instant.EPOCH);
        assertThat(o.toString()).doesNotContain("Nguyen", "0901234567", "Le Loi", "Ho Chi Minh");
    }
}
