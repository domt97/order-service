package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.service.checkout.CheckoutTransactions;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase.RunResult;
import com.dotran.oms.order.infrastructure.message.StockReplyConsumers;
import com.dotran.oms.order.support.ContractSchemaFiles;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.Sagas;
import com.dotran.oms.order.support.Sagas.Placed;
import com.dotran.oms.order.support.WalSlot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The re-send job's run on real Postgres (ADR 0006 obligation 2): doubling, attempt 3, never cancels, a poisoned row. */
class StockCommandResendJobIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private StockCommandResendService service;

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
        wal = WalSlot.create(jdbc, "order_resend_it");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private Placed placedAndConfirmSent() throws Exception {
        Placed p = Sagas.place(transactions, customer, merchant, storeA, storeB);
        Sagas.deliver(consumers, Sagas.reserved(p.order(storeA)));
        Sagas.deliver(consumers, Sagas.reserved(p.order(storeB)));
        wal.changes();
        return p;
    }

    private void makeDue(UUID orderId) {
        jdbc.update("UPDATE orders SET stock_due_at = now() - interval '1 second' WHERE id = ?", orderId);
    }

    private Map<String, Object> stock(UUID orderId) {
        return jdbc.queryForMap("SELECT status, stock_state, stock_attempts, "
                + "extract(epoch FROM stock_due_at - now()) AS due_in FROM orders WHERE id = ?", orderId);
    }

    private List<WalSlot.Change> inserts(String type) {
        return wal.changes().stream().filter(c -> c.isOutbox() && "INSERT".equals(c.op())
                && c.data().contains("event_type[character varying]:'" + type + "'")).toList();
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("Confirm re-sent when due: 150 s → 300 s → 600 s → 600 s, attempt 3 counted, the order stays PLACED")
    void doubling() throws Exception {
        Placed p = placedAndConfirmSent();
        UUID a = p.order(storeA).id().getValue();
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        assertThat(inserts("ConfirmStockReservation")).isEmpty(); // not due yet
        double over3 = counter("order.saga.stock.command.attempts.over3", "type", "ConfirmStockReservation",
                "path", "RESENT");

        long[] expected = {300, 600, 600};
        for (int attempt = 1; attempt <= 3; attempt++) {
            makeDue(a);
            assertThat(service.run()).isEqualTo(RunResult.CLEAN);
            List<WalSlot.Change> confirms = inserts("ConfirmStockReservation");
            assertThat(confirms).singleElement().satisfies(c -> {
                assertThat(c.data()).contains("aggregate_id[uuid]:'" + a + "'");
                assertThat(c.data()).contains("merchant_id[uuid]:'" + merchant + "'");
                assertThat(ContractSchemaFiles.validate("ConfirmStockReservation", JSON.readTree(c.payload())))
                        .isEmpty();
            });
            Map<String, Object> row = stock(a);
            assertThat(row.get("status")).isEqualTo("PLACED");
            assertThat(row.get("stock_state")).isEqualTo("CONFIRM_SENT");
            assertThat(row.get("stock_attempts")).isEqualTo(attempt);
            assertThat(((Number) row.get("due_in")).doubleValue()).isCloseTo(expected[attempt - 1], within(5.0));
        }
        assertThat(counter("order.saga.stock.command.attempts.over3", "type", "ConfirmStockReservation",
                "path", "RESENT")).isEqualTo(over3 + 1);
        // The other order was never due: untouched.
        assertThat(stock(p.order(storeB).id().getValue()).get("stock_attempts")).isEqualTo(0);
    }

    @Test
    @DisplayName("a Release after ConfirmationFailed is re-sent with its stored restock and reason; Released settles it")
    void releaseFromConfirmationFailed() throws Exception {
        Placed p = placedAndConfirmSent();
        Sagas.deliver(consumers, Sagas.confirmationFailed(p.order(storeA), "INSUFFICIENT_STOCK"));
        wal.changes();
        UUID a = p.order(storeA).id().getValue();
        makeDue(a);
        service.run();
        List<WalSlot.Change> releases = inserts("ReleaseStockReservation");
        assertThat(releases).singleElement().satisfies(c -> {
            var payload = JSON.readTree(c.payload());
            assertThat(ContractSchemaFiles.validate("ReleaseStockReservation", payload)).isEmpty();
            assertThat(payload.get("restock").asBoolean()).isTrue();
            assertThat(payload.get("reason").asText()).isEqualTo("STOCK_NOT_CONFIRMED");
        });
        Sagas.deliver(consumers, Sagas.released(p.order(storeA)));
        makeDue(a); // a due time on a RELEASED order is never picked: the scan reads CONFIRM_SENT/RELEASE_SENT only
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        assertThat(inserts("ReleaseStockReservation")).isEmpty();
        assertThat(stock(a).get("stock_state")).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("one poisoned order in a batch: the others re-sent, the bad one counted once, deferred with attempts + 1")
    void poisoned() throws Exception {
        Placed p = placedAndConfirmSent();
        UUID a = p.order(storeA).id().getValue();
        UUID b = p.order(storeB).id().getValue();
        // A Release whose stored reason the command factory refuses: fails every time.
        jdbc.update("UPDATE orders SET stock_state = 'RELEASE_SENT', release_restock = true, release_reason = '' "
                + "WHERE id = ?", a);
        makeDue(a);
        makeDue(b);
        double failures = counter("order.saga.job.failure", "job", "stock-resend", "kind", "PERSISTENT");

        assertThat(service.run()).isEqualTo(RunResult.CLEAN);

        assertThat(inserts("ConfirmStockReservation")).singleElement()
                .satisfies(c -> assertThat(c.data()).contains("aggregate_id[uuid]:'" + b + "'"));
        assertThat(stock(b).get("stock_attempts")).isEqualTo(1);
        Map<String, Object> bad = stock(a);
        assertThat(bad.get("stock_attempts")).isEqualTo(1);
        assertThat(((Number) bad.get("due_in")).doubleValue()).isCloseTo(300, within(5.0));
        assertThat(counter("order.saga.job.failure", "job", "stock-resend", "kind", "PERSISTENT"))
                .isEqualTo(failures + 1);
    }
}
