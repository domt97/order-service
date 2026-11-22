package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.service.checkout.CheckoutTransactions;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase.RunResult;
import com.dotran.oms.order.infrastructure.message.StockReplyConsumers;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.Sagas;
import com.dotran.oms.order.support.Sagas.Placed;
import com.dotran.oms.order.support.WalSlot;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The reserve-timeout job's run on real Postgres (D3): SKIP LOCKED, the decision, a poisoned checkout, the gauge. */
class ReserveTimeoutJobIT extends PostgresIT {

    @Autowired
    private ReserveTimeoutService service;

    @Autowired
    private CheckoutTransactions transactions;

    @Autowired
    private StockReplyConsumers consumers;

    @Autowired
    private MeterRegistry registry;

    private WalSlot wal;
    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();

    @BeforeEach
    void slot() {
        wal = WalSlot.create(jdbc, "order_reserve_timeout_it");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private void due(Placed p, int secondsAgo) {
        jdbc.update("UPDATE checkouts SET reserve_due_at = now() - make_interval(secs => ?) WHERE id = ?",
                secondsAgo, p.checkout().id().getValue());
    }

    private String status(Placed p) {
        return jdbc.queryForObject("SELECT status FROM checkouts WHERE id = ?", String.class,
                p.checkout().id().getValue());
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private List<WalSlot.Change> inserts(List<WalSlot.Change> changes, String type) {
        return changes.stream().filter(c -> c.isOutbox() && "INSERT".equals(c.op())
                && c.data().contains("event_type[character varying]:'" + type + "'")).toList();
    }

    @Test
    @DisplayName("deadline passed, A reserved and B unanswered: REJECTED with RESERVE_TIMEOUT, Release for both, slots back")
    void timesOut() throws Exception {
        Placed p = Sagas.place(transactions, customer, merchant, storeA, storeB);
        Sagas.deliver(consumers, Sagas.reserved(p.order(storeA)));
        wal.changes();
        double timeouts = counter("order.saga.reserve.timeout");

        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        assertThat(status(p)).isEqualTo("PENDING"); // not due yet
        due(p, 1);
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);

        assertThat(status(p)).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM orders WHERE checkout_id = ? AND status = 'REJECTED' AND stock_state = "
                + "'RELEASE_SENT' AND release_reason = 'RESERVE_TIMEOUT' AND release_restock AND stock_due_at > now()",
                p.checkout().id().getValue())).isEqualTo(2);
        assertThat(count("SELECT coalesce(sum(placed_count), 0) FROM store_day_counters WHERE merchant_id = ?",
                merchant)).isZero();
        List<WalSlot.Change> changes = wal.changes();
        assertThat(inserts(changes, "ReleaseStockReservation")).hasSize(2);
        assertThat(inserts(changes, "CheckoutRejected")).singleElement()
                .satisfies(c -> assertThat(c.payload()).contains("RESERVE_TIMEOUT").contains(storeB.toString()));
        assertThat(counter("order.saga.reserve.timeout")).isEqualTo(timeouts + 1);

        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        assertThat(inserts(wal.changes(), "CheckoutRejected")).isEmpty();
    }

    @Test
    @DisplayName("deadline passed with every order RESERVED: placed, not rejected")
    void allReservedPlaces() {
        Placed p = Sagas.place(transactions, customer, merchant, storeA, storeB);
        jdbc.update("UPDATE orders SET stock_state = 'RESERVED' WHERE checkout_id = ?", p.checkout().id().getValue());
        due(p, 1);
        service.run();
        assertThat(status(p)).isEqualTo("PLACED");
        assertThat(count("SELECT count(*) FROM orders WHERE checkout_id = ? AND stock_state = 'CONFIRM_SENT'",
                p.checkout().id().getValue())).isEqualTo(2);
    }

    @Test
    @DisplayName("a poisoned checkout first: counted, deferred 60 s, still PENDING and on the gauge; the next one decided")
    void poisoned() {
        Placed bad = Sagas.place(transactions, customer, merchant, storeA);
        Placed good = Sagas.place(transactions, customer, merchant, storeB);
        // Finalize needs the store's day counter: without it the checkout fails every time.
        jdbc.update("UPDATE orders SET stock_state = 'RESERVED' WHERE checkout_id = ?", bad.checkout().id().getValue());
        jdbc.update("DELETE FROM store_day_counters WHERE store_id = ?", storeA);
        due(bad, 10);
        due(good, 5);
        jdbc.update("UPDATE checkouts SET created_at = now() - interval '10 minutes' WHERE id = ?",
                bad.checkout().id().getValue());
        double failures = counter("order.saga.job.failure", "job", "reserve-timeout", "kind", "PERSISTENT");

        assertThat(service.run()).isEqualTo(RunResult.CLEAN);

        assertThat(status(good)).isEqualTo("REJECTED");
        assertThat(status(bad)).isEqualTo("PENDING");
        OffsetDateTime dueAt = jdbc.queryForObject("SELECT reserve_due_at FROM checkouts WHERE id = ?",
                OffsetDateTime.class, bad.checkout().id().getValue());
        OffsetDateTime dbNow = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
        assertThat(dueAt.toEpochSecond()).isCloseTo(dbNow.plusSeconds(60).toEpochSecond(), within(5L));
        assertThat(count("SELECT count(*) FROM orders WHERE checkout_id = ? AND stock_state = 'RESERVED'",
                bad.checkout().id().getValue())).isEqualTo(1);
        assertThat(counter("order.saga.job.failure", "job", "reserve-timeout", "kind", "PERSISTENT"))
                .isEqualTo(failures + 1);
        assertThat(registry.find("order.saga.job.consecutive.aborts").tag("job", "reserve-timeout").gauge().value())
                .isZero();

        // The next run reads the gauge: the deferred checkout still ages by created_at.
        service.run();
        assertThat(registry.find("order.saga.oldest.pending.age.seconds").gauge().value())
                .isGreaterThanOrEqualTo(590);
    }

    @Test
    @DisplayName("two workers at once over 6 due checkouts: each decided exactly once")
    void twoWorkers() throws Exception {
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Placed p = Sagas.place(transactions, customer, merchant, UUID.randomUUID());
            due(p, 1);
            placed.add(p);
        }
        wal.changes();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<RunResult>> runs = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return service.run();
                }));
            }
            start.countDown();
            for (Future<RunResult> run : runs) {
                assertThat(run.get(30, TimeUnit.SECONDS)).isEqualTo(RunResult.CLEAN);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(placed).allSatisfy(p -> assertThat(status(p)).isEqualTo("REJECTED"));
        List<WalSlot.Change> changes = wal.changes();
        assertThat(inserts(changes, "CheckoutRejected")).hasSize(6);
        assertThat(inserts(changes, "ReleaseStockReservation")).hasSize(6);
    }
}
