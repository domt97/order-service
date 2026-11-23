package com.dotran.oms.order.domain.valueobject;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BY_STORE_ID (the counter lock order)")
class StoreOrderTest {

    /** Text order puts LOW first; UUID.compareTo (signed longs) puts HIGH first: the two orders differ. */
    private static final UUID LOW = UUID.fromString("10000000-0000-0000-0000-000000000000");
    private static final UUID HIGH = UUID.fromString("80000000-0000-0000-0000-000000000000");

    private static Order order(UUID store) {
        return order(store, CheckoutId.newId(), CustomerId.of(UUID.randomUUID()));
    }

    private static Order order(UUID store, CheckoutId checkout, CustomerId customer) {
        OrderLine line = OrderLine.snapshot(UUID.randomUUID(), ProductId.of(UUID.randomUUID()), "TS-M", "Tee", "M", 1,
                new BigDecimal("1000"), "VND", 0);
        return Order.pending(checkout, customer, MerchantId.of(UUID.randomUUID()),
                StoreId.of(store), "HCM01", LocalDate.of(2026, 11, 21), "VND", UUID.randomUUID(),
                new DeliverySnapshot("n", "p", "l", null, null, null, "c", null, "VN"), List.of(line), Instant.now());
    }

    @Test
    @DisplayName("sorts by the uuid text (Postgres uuid order), not by UUID.compareTo")
    void textOrder() {
        assertThat(HIGH.compareTo(LOW)).isNegative(); // the trap: Java's order differs
        assertThat(Stream.of(order(HIGH), order(LOW)).sorted(Order.BY_STORE_ID).map(o -> o.storeId().getValue()))
                .containsExactly(LOW, HIGH);
        assertThat(Stream.of(new StoreOrder(order(HIGH), null), new StoreOrder(order(LOW), 5))
                .sorted(StoreOrder.BY_STORE_ID).map(s -> s.order().storeId().getValue())).containsExactly(LOW, HIGH);
    }

    @Test
    @DisplayName("the placement plan uses the same order")
    void planOrder() {
        CustomerId customer = CustomerId.of(UUID.randomUUID());
        var checkout = com.dotran.oms.order.domain.model.Checkout.pending(customer,
                com.dotran.oms.order.common.id.CartId.of(UUID.randomUUID()), 1, "a".repeat(64),
                com.dotran.oms.core.domain.id.EventId.of(UUID.randomUUID()), Instant.now(), Instant.now(),
                Instant.now().plusSeconds(150));
        Order high = order(HIGH, checkout.id(), customer);
        Order low = order(LOW, checkout.id(), customer);
        PlacementPlan plan = new PlacementPlan(checkout, List.of(new StoreOrder(high, null), new StoreOrder(low, null)));
        assertThat(plan.orders()).extracting(o -> o.storeId().getValue()).containsExactly(LOW, HIGH);
    }
}
