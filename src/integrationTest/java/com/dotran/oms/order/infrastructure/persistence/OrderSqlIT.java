package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.repository.OrderRepository;
import com.dotran.oms.order.application.repository.OrderRepository.Rescheduled;
import com.dotran.oms.order.application.repository.OrderTransition;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.dotran.oms.order.support.Orders;
import com.dotran.oms.order.support.PostgresIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSqlIT extends PostgresIT {

    private static final Duration INITIAL = Duration.ofSeconds(150);
    private static final Duration MAX = Duration.ofMinutes(10);

    @Autowired
    private OrderRepository orders;

    @Autowired
    private CheckoutRepository checkouts;

    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();
    private Instant now;
    private Checkout checkout;
    private Order a;
    private Order b;

    @BeforeEach
    void data() {
        now = Orders.now();
        checkout = Orders.pendingCheckout(customer, now);
        checkouts.insertIfAbsent(checkout);
        a = Orders.pending(checkout.id(), customer, merchant, storeA, now,
                Orders.line("TS-M", "10000", 2), Orders.line("TS-L", "12000", 1));
        b = Orders.pending(checkout.id(), customer, merchant, storeB, now, Orders.line("MUG", "50000", 1));
        tx.executeWithoutResult(s -> orders.insertAll(List.of(a, b)));
    }

    private OrderTransition.OrderTransitionBuilder move(Order o, OrderStatus fromStatus, StockState fromStock,
                                                        OrderStatus toStatus, StockState toStock) {
        return OrderTransition.builder().orderId(o.id()).merchantId(o.merchantId()).checkoutId(o.checkoutId())
                .fromStatus(fromStatus).fromStock(fromStock).toStatus(toStatus).toStock(toStock).now(now);
    }

    private boolean apply(OrderTransition t) {
        return Boolean.TRUE.equals(inTx(() -> orders.transition(t)));
    }

    private Order reload(Order o) {
        return orders.findByCheckout(o.checkoutId(), o.customerId()).stream()
                .filter(x -> x.id().equals(o.id())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("insertAll then findByCheckout: every field and line round-trips, id order; first history row")
    void roundTrip() {
        List<Order> read = orders.findByCheckout(checkout.id(), CustomerId.of(customer));
        List<Order> expected = List.of(a, b).stream()
                .sorted(Comparator.comparing(o -> o.id().getValue().toString())).toList(); // Postgres uuid order
        assertThat(read).usingRecursiveComparison()
                .withComparatorForType(Comparator.<java.math.BigDecimal>naturalOrder(), java.math.BigDecimal.class)
                .ignoringCollectionOrderInFields("lines")
                .isEqualTo(expected);
        assertThat(count("SELECT count(*) FROM order_transitions WHERE from_status IS NULL AND to_status = 'PENDING' "
                + "AND reason = 'PLACEMENT'")).isEqualTo(2);
        assertThat(inTx(() -> orders.lockByCheckout(checkout.id(), CustomerId.of(customer))))
                .extracting(Order::id).containsExactlyElementsOf(expected.stream().map(Order::id).toList());
    }

    @Test
    @DisplayName("scoping: wrong merchant or store → no key; another customer → no orders")
    void scoping() {
        assertThat(orders.findScoped(a.id(), a.merchantId(), a.storeId())).hasValueSatisfying(k -> {
            assertThat(k.checkoutId()).isEqualTo(checkout.id());
            assertThat(k.customerId()).isEqualTo(CustomerId.of(customer));
        });
        assertThat(orders.findScoped(a.id(), MerchantId.of(UUID.randomUUID()), a.storeId())).isEmpty();
        assertThat(orders.findScoped(a.id(), a.merchantId(), StoreId.of(storeB))).isEmpty();
        assertThat(orders.findByCheckout(checkout.id(), CustomerId.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("guarded transition: applies once; wrong stock state, wrong status, wrong merchant or checkout → 0 rows")
    void guarded() {
        OrderTransition reserved = move(a, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.PENDING,
                StockState.RESERVED).reserveExpiresAt(now.plusSeconds(900)).build();
        assertThat(apply(reserved)).isTrue();
        assertThat(apply(reserved)).isFalse();

        // stock state matches, status does not
        assertThat(apply(move(b, OrderStatus.PLACED, StockState.RESERVE_SENT, OrderStatus.PENDING,
                StockState.RESERVED).build())).isFalse();
        // wrong merchant / checkout
        assertThat(apply(OrderTransition.builder().orderId(b.id()).merchantId(MerchantId.of(UUID.randomUUID()))
                .checkoutId(b.checkoutId()).fromStatus(OrderStatus.PENDING).fromStock(StockState.RESERVE_SENT)
                .toStatus(OrderStatus.PENDING).toStock(StockState.RESERVED).now(now).build())).isFalse();
        assertThat(apply(OrderTransition.builder().orderId(b.id()).merchantId(b.merchantId())
                .checkoutId(com.dotran.oms.order.common.id.CheckoutId.newId()).fromStatus(OrderStatus.PENDING)
                .fromStock(StockState.RESERVE_SENT).toStatus(OrderStatus.PENDING).toStock(StockState.RESERVED)
                .now(now).build())).isFalse();
        assertThat(reload(b).stockState()).isEqualTo(StockState.RESERVE_SENT);
        assertThat(reload(a).reserveExpiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(count("SELECT count(*) FROM order_transitions")).isEqualTo(2); // status did not change
    }

    @Test
    @DisplayName("finalize then confirm: number, due time set and attempts reset; CONFIRMED clears the due time")
    void finalizeAndConfirm() {
        apply(move(a, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.PENDING, StockState.RESERVED).build());
        assertThat(apply(move(a, OrderStatus.PENDING, StockState.RESERVED, OrderStatus.PLACED,
                StockState.CONFIRM_SENT).stockDueAt(now.plusSeconds(150)).orderSeq(1)
                .orderNumber("HCM01-261121-001").placedAt(now).build())).isTrue();
        Order placed = reload(a);
        assertThat(placed.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(placed.orderNumber()).isEqualTo("HCM01-261121-001");
        assertThat(placed.stockDueAt()).isEqualTo(now.plusSeconds(150));
        assertThat(placed.stockAttempts()).isZero();
        assertThat(count("SELECT count(*) FROM order_transitions WHERE order_id = ? AND from_status = 'PENDING' "
                + "AND to_status = 'PLACED'", a.id().getValue())).isEqualTo(1);

        assertThat(apply(move(a, OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.PLACED,
                StockState.CONFIRMED).confirmedAt(now).build())).isTrue();
        assertThat(reload(a).stockDueAt()).isNull();
        assertThat(reload(a).confirmedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("reject: reasons stored as JSONB; RESERVE_SENT → RELEASE_SENT keeps restock and reason")
    void rejectAndRelease() {
        List<RejectionReason> why = List.of(RejectionReason.line(RejectionCode.INSUFFICIENT_STOCK, storeB, "MUG"));
        assertThat(apply(move(b, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.REJECTED,
                StockState.RESERVE_REJECTED).reserveRejection(why).reason("CHECKOUT_REJECTED").build())).isTrue();
        assertThat(reload(b).reserveRejection()).isEqualTo(why);
        assertThat(apply(move(a, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.REJECTED,
                StockState.RELEASE_SENT).stockDueAt(now.plusSeconds(150)).releaseRestock(true)
                .releaseReason("RESERVE_TIMEOUT").build())).isTrue();
        Order released = reload(a);
        assertThat(released.releaseRestock()).isTrue();
        assertThat(released.releaseReason()).isEqualTo("RESERVE_TIMEOUT");
        assertThat(released.stockDueAt()).isEqualTo(now.plusSeconds(150));
    }

    @Test
    @DisplayName("due scan: only CONFIRM_SENT/RELEASE_SENT that are due, excluded skipped; relockDue guards state and due")
    void dueScan() {
        apply(move(a, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.REJECTED, StockState.RELEASE_SENT)
                .stockDueAt(now.minusSeconds(1)).releaseRestock(true).releaseReason("RESERVE_TIMEOUT").build());
        apply(move(b, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.REJECTED, StockState.RELEASE_SENT)
                .stockDueAt(now.plusSeconds(100)).releaseRestock(true).releaseReason("RESERVE_TIMEOUT").build());

        assertThat(inTx(() -> orders.lockDueStockCommands(now, 50, Set.of()))).extracting(Order::id)
                .containsExactly(a.id());
        assertThat(inTx(() -> orders.lockDueStockCommands(now, 50, Set.of(a.id().getValue(),
                UUID.randomUUID())))).isEmpty();
        assertThat(inTx(() -> orders.lockDueStockCommands(now, 50, Set.of())).get(0).lines()).hasSize(2);
        assertThat(inTx(() -> orders.relockDue(a.id(), a.merchantId(), now))).isPresent();
        assertThat(inTx(() -> orders.relockDue(b.id(), b.merchantId(), now))).isEmpty(); // not due
        assertThat(inTx(() -> orders.relockDue(a.id(), MerchantId.of(UUID.randomUUID()), now))).isEmpty();

        apply(move(a, OrderStatus.REJECTED, StockState.RELEASE_SENT, OrderStatus.REJECTED, StockState.RELEASED)
                .build());
        assertThat(reload(a).stockDueAt()).isNull();
        assertThat(inTx(() -> orders.relockDue(a.id(), a.merchantId(), now))).isEmpty(); // settled
    }

    @Test
    @DisplayName("reschedule: attempts + 1, interval doubles to the cap; settled or other merchant → empty")
    void reschedule() {
        apply(move(a, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.REJECTED, StockState.RELEASE_SENT)
                .stockDueAt(now).releaseRestock(true).releaseReason("RESERVE_TIMEOUT").build());
        assertThat(inTx(() -> orders.reschedule(a.id(), a.merchantId(), now, INITIAL, MAX)))
                .contains(new Rescheduled(1, StockState.RELEASE_SENT));
        assertThat(reload(a).stockDueAt()).isEqualTo(now.plusSeconds(300));
        inTx(() -> orders.reschedule(a.id(), a.merchantId(), now, INITIAL, MAX));
        assertThat(reload(a).stockDueAt()).isEqualTo(now.plusSeconds(600));
        assertThat(inTx(() -> orders.reschedule(a.id(), a.merchantId(), now, INITIAL, MAX)))
                .contains(new Rescheduled(3, StockState.RELEASE_SENT));
        assertThat(reload(a).stockDueAt()).isEqualTo(now.plusSeconds(600)); // capped at 10 min

        assertThat(inTx(() -> orders.reschedule(a.id(), MerchantId.of(UUID.randomUUID()), now, INITIAL, MAX)))
                .isEmpty();
        apply(move(a, OrderStatus.REJECTED, StockState.RELEASE_SENT, OrderStatus.REJECTED, StockState.RELEASED)
                .build());
        assertThat(inTx(() -> orders.reschedule(a.id(), a.merchantId(), now, INITIAL, MAX))).isEmpty();
        assertThat(reload(a).stockDueAt()).isNull();
        assertThat(reload(a).stockAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("reschedule after 2000 attempts: no overflow, due = now + max, attempts 2001")
    void rescheduleManyAttempts() {
        apply(move(a, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.REJECTED, StockState.RELEASE_SENT)
                .stockDueAt(now).releaseRestock(true).releaseReason("RESERVE_TIMEOUT").build());
        jdbc.update("UPDATE orders SET stock_attempts = 2000 WHERE id = ?", a.id().getValue());
        assertThat(inTx(() -> orders.reschedule(a.id(), a.merchantId(), now, INITIAL, MAX)))
                .contains(new Rescheduled(2001, StockState.RELEASE_SENT));
        assertThat(reload(a).stockDueAt()).isEqualTo(now.plus(MAX));
    }

    @Test
    @DisplayName("a status-only change (stock state kept) keeps the stored due time and attempts")
    void statusOnlyKeepsDue() {
        jdbc.update("UPDATE orders SET status = 'PLACED', stock_state = 'CONFIRM_SENT', order_seq = 1, "
                + "order_number = 'HCM01-261121-001', stock_due_at = ?, stock_attempts = 2 WHERE id = ?",
                java.sql.Timestamp.from(now.plusSeconds(77)), a.id().getValue());
        assertThat(apply(move(a, OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.CANCELLED,
                StockState.CONFIRM_SENT).build())).isTrue();
        Order after = reload(a);
        assertThat(after.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(after.stockDueAt()).isEqualTo(now.plusSeconds(77));
        assertThat(after.stockAttempts()).isEqualTo(2);
    }
}
