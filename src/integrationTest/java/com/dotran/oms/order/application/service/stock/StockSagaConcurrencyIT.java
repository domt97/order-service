package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.service.checkout.CheckoutTransactions;
import com.dotran.oms.order.infrastructure.message.StockReplyConsumers;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.Sagas;
import com.dotran.oms.order.support.Sagas.Placed;
import com.dotran.oms.order.support.WalSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Replies of different topics run on different threads: the checkout row lock serialises them (ADR 0007). */
class StockSagaConcurrencyIT extends PostgresIT {

    @Autowired
    private StockReplyConsumers consumers;

    @Autowired
    private CheckoutTransactions transactions;

    private WalSlot wal;
    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private final UUID storeA = UUID.randomUUID();
    private final UUID storeB = UUID.randomUUID();

    @BeforeEach
    void slot() {
        wal = WalSlot.create(jdbc, "order_saga_concurrency_it");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    /** Runs the deliveries at the same moment; returns once all ended, rethrowing the first failure. */
    private void together(String... replies) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(replies.length);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (String reply : replies) {
                Callable<Object> task = () -> {
                    start.await();
                    Sagas.deliver(consumers, reply);
                    return null;
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            for (Future<Object> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private long outbox(String type) {
        return wal.changes().stream().filter(c -> c.isOutbox() && "INSERT".equals(c.op())
                && c.data().contains("event_type[character varying]:'" + type + "'")).count();
    }

    @RepeatedTest(5)
    @DisplayName("the last two reserve replies at once: exactly one CheckoutPlaced, both orders numbered")
    void twoReserved() throws Exception {
        Placed p = Sagas.place(transactions, customer, merchant, storeA, storeB);
        together(Sagas.reserved(p.order(storeA)), Sagas.reserved(p.order(storeB)));
        assertThat(outbox("CheckoutPlaced")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM orders WHERE order_number IS NOT NULL")).isEqualTo(2);
    }

    @RepeatedTest(5)
    @DisplayName("a reject and a reserve at once: exactly one CheckoutRejected, with the reject's reason")
    void rejectAndReserve() throws Exception {
        Placed p = Sagas.place(transactions, customer, merchant, storeA, storeB);
        together(Sagas.reserved(p.order(storeA)), Sagas.rejected(p.order(storeB), null, "NOT_ORDERABLE"));
        List<WalSlot.Change> changes = wal.changes();
        assertThat(changes.stream().filter(c -> c.isOutbox() && "INSERT".equals(c.op())
                && c.data().contains("'CheckoutRejected'")).toList()).singleElement()
                .satisfies(c -> assertThat(c.payload()).contains("NOT_ORDERABLE"));
        assertThat(changes.stream().filter(c -> c.isOutbox() && c.data().contains("'CheckoutPlaced'"))).isEmpty();
        assertThat(count("SELECT placed_count FROM store_day_counters WHERE store_id = ?", storeA)).isZero();
    }

    @RepeatedTest(5)
    @DisplayName("checkout 1 placing (A, B) while checkout 2 is rejected (B, A): both decided, no deadlock")
    void noDeadlock() throws Exception {
        Placed one = Sagas.place(transactions, customer, merchant, storeA, storeB);
        Placed two = Sagas.place(transactions, customer, merchant, storeB, storeA);
        Sagas.deliver(consumers, Sagas.reserved(one.order(storeA)));
        Sagas.deliver(consumers, Sagas.reserved(two.order(storeA)));
        together(Sagas.reserved(one.order(storeB)), Sagas.rejected(two.order(storeB), null, "INSUFFICIENT_STOCK"));
        assertThat(jdbc.queryForObject("SELECT status FROM checkouts WHERE id = ?", String.class,
                one.checkout().id().getValue())).isEqualTo("PLACED");
        assertThat(jdbc.queryForObject("SELECT status FROM checkouts WHERE id = ?", String.class,
                two.checkout().id().getValue())).isEqualTo("REJECTED");
        assertThat(count("SELECT placed_count FROM store_day_counters WHERE store_id = ?", storeA)).isEqualTo(1);
    }
}
