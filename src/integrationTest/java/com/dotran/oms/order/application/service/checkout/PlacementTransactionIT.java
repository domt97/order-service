package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.service.checkout.CheckoutTransactions.PlaceResult;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.PlacementPlan;
import com.dotran.oms.order.domain.valueobject.StoreOrder;
import com.dotran.oms.order.support.ContractSchemaFiles;
import com.dotran.oms.order.support.Orders;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.WalSlot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CheckoutTransactions.place and republishPlaced on real Postgres; the outbox as Debezium would read it. */
class PlacementTransactionIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CheckoutTransactions transactions;

    @Autowired
    private CheckoutRepository checkouts;

    private WalSlot wal;
    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();

    @BeforeEach
    void slot() {
        wal = WalSlot.create(jdbc, "order_placement_it");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private PlacementPlan plan(Checkout checkout, Integer max, int stores, int lines) {
        Instant now = checkout.createdAt();
        return new PlacementPlan(checkout, IntStream.range(0, stores).mapToObj(i -> new StoreOrder(Orders.pending(
                checkout.id(), customer, merchant, UUID.randomUUID(), now,
                IntStream.range(0, lines).mapToObj(j -> Orders.line("SKU-" + j, "1000", 1))
                        .toArray(OrderLine[]::new)), max)).toList());
    }

    private List<WalSlot.Change> outboxInserts() {
        return wal.changes().stream().filter(c -> c.isOutbox() && "INSERT".equals(c.op())).toList();
    }

    @Test
    @DisplayName("place: PENDING checkout, orders, lines, history, one ReserveStock per order and one slot per store")
    void placed() throws Exception {
        Checkout checkout = Orders.pendingCheckout(customer, Orders.now());
        PlacementPlan plan = plan(checkout, null, 2, 3);
        assertThat(transactions.place(plan, checkout.createdAt())).isEqualTo(PlaceResult.PLACEMENT_STARTED);

        assertThat(count("SELECT count(*) FROM checkouts WHERE id = ? AND status = 'PENDING'",
                checkout.id().getValue())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders WHERE checkout_id = ?", checkout.id().getValue())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM order_lines")).isEqualTo(6);
        assertThat(count("SELECT count(*) FROM order_transitions WHERE to_status = 'PENDING'")).isEqualTo(2);
        assertThat(count("SELECT sum(placed_count) FROM store_day_counters WHERE merchant_id = ?", merchant))
                .isEqualTo(2);
        List<WalSlot.Change> inserts = outboxInserts();
        assertThat(inserts).hasSize(2);
        for (WalSlot.Change c : inserts) {
            assertThat(c.data()).contains("event_type[character varying]:'ReserveStock'");
            assertThat(c.data()).contains("merchant_id[uuid]:'" + merchant + "'");
            assertThat(ContractSchemaFiles.validate("ReserveStock", JSON.readTree(c.payload()))).isEmpty();
        }
    }

    @Test
    @DisplayName("a capped store: DailyCapReached names it; nothing committed (no checkout, order, slot or outbox row)")
    void capRollsBack() {
        Checkout checkout = Orders.pendingCheckout(customer, Orders.now());
        PlacementPlan free = plan(checkout, null, 1, 1);
        Order cappedOrder = Orders.pending(checkout.id(), customer, merchant, UUID.randomUUID(), checkout.createdAt(),
                Orders.line("A", "1000", 1));
        PlacementPlan mixed = new PlacementPlan(checkout, List.of(free.stores().get(0),
                new StoreOrder(cappedOrder, 0)));

        assertThatThrownBy(() -> transactions.place(mixed, checkout.createdAt()))
                .isInstanceOfSatisfying(DailyCapReached.class, e ->
                        assertThat(e.storeIds()).containsExactly(cappedOrder.storeId().getValue()));
        assertThat(count("SELECT count(*) FROM checkouts")).isZero();
        assertThat(count("SELECT count(*) FROM orders")).isZero();
        assertThat(count("SELECT count(*) FROM store_day_counters")).isZero();
        assertThat(outboxInserts()).isEmpty();
    }

    @Test
    @DisplayName("the key already has a row: KEY_TAKEN, nothing else written")
    void keyTaken() {
        Instant now = Orders.now();
        Checkout first = Orders.pendingCheckout(customer, now);
        checkouts.insertIfAbsent(first);
        Checkout second = Checkout.pending(CustomerId.of(customer), first.cartId(), 3, "b".repeat(64),
                EventId.of(UUID.randomUUID()), now, now, now.plusSeconds(150));
        assertThat(transactions.place(plan(second, null, 1, 1), now)).isEqualTo(PlaceResult.KEY_TAKEN);
        assertThat(count("SELECT count(*) FROM orders")).isZero();
        assertThat(count("SELECT count(*) FROM store_day_counters")).isZero();
        assertThat(outboxInserts()).isEmpty();
    }

    @Test
    @DisplayName("lock_timeout applies: a store's counter row held by another transaction fails the placement in ~2 s")
    void lockTimeout() throws Exception {
        Checkout checkout = Orders.pendingCheckout(customer, Orders.now());
        PlacementPlan p = plan(checkout, null, 1, 1);
        Order o = p.orders().get(0);
        jdbc.update("INSERT INTO store_day_counters VALUES (?, ?, ?, 0, 0)", o.storeId().getValue(),
                java.sql.Date.valueOf(o.localDate()), merchant);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT 1 FROM store_day_counters WHERE store_id = ? FOR UPDATE",
                        o.storeId().getValue());
                locked.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();
            assertThatThrownBy(() -> transactions.place(p, checkout.createdAt()))
                    .isInstanceOf(DataAccessException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM checkouts")).isZero();
        assertThat(count("SELECT count(*) FROM orders")).isZero();
    }

    @Test
    @DisplayName("10 stores × 100 lines commit inside the 3 s statement and 10 s transaction timeouts")
    void largest() {
        Checkout checkout = Orders.pendingCheckout(customer, Orders.now());
        assertThat(transactions.place(plan(checkout, null, 10, 100), checkout.createdAt()))
                .isEqualTo(PlaceResult.PLACEMENT_STARTED);
        assertThat(count("SELECT count(*) FROM order_lines")).isEqualTo(1000);
        assertThat(outboxInserts()).hasSize(10);
    }

    @Test
    @DisplayName("republishPlaced: CheckoutPlaced with every order number, causation = the new command, keyed by cartId")
    void republishPlaced() throws Exception {
        Checkout checkout = Orders.pendingCheckout(customer, Orders.now());
        transactions.place(plan(checkout, null, 2, 1), checkout.createdAt());
        jdbc.update("UPDATE orders SET status = 'PLACED', stock_state = 'CONFIRMED', order_seq = 1, "
                + "order_number = store_code || '-261121-001' WHERE checkout_id = ?", checkout.id().getValue());
        jdbc.update("UPDATE checkouts SET status = 'PLACED', placed_at = now() WHERE id = ?", checkout.id().getValue());
        Checkout placed = checkouts.findByKey(checkout.cartId(), 3, CustomerId.of(customer)).orElseThrow();
        wal.changes(); // skip the placement rows

        EventId copy = EventId.of(UUID.randomUUID());
        transactions.republishPlaced(placed, copy, Orders.now());
        List<WalSlot.Change> inserts = outboxInserts();
        assertThat(inserts).singleElement().satisfies(c -> {
            assertThat(c.data()).contains("event_type[character varying]:'CheckoutPlaced'");
            assertThat(c.data()).contains("aggregate_id[uuid]:'" + checkout.cartId().getValue() + "'");
            var payload = JSON.readTree(c.payload());
            assertThat(ContractSchemaFiles.validate("CheckoutPlaced", payload)).isEmpty();
            assertThat(payload.get("causationId").asText()).isEqualTo(copy.getValue().toString());
            assertThat(payload.get("orders")).hasSize(2);
        });
    }
}
