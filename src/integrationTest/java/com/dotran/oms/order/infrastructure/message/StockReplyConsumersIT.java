package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.order.application.service.checkout.CheckoutTransactions;
import com.dotran.oms.order.application.service.stock.InvalidStockReplyException;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.support.ContractSchemaFiles;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.Sagas;
import com.dotran.oms.order.support.Sagas.Placed;
import com.dotran.oms.order.support.WalSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The five reply listeners on the real services and Postgres (no broker): JSON reply in → order, checkout and counter
 * rows, and the commands and replies as Debezium would read them from the WAL.
 */
class StockReplyConsumersIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private StockReplyConsumers consumers;

    @Autowired
    private CheckoutTransactions transactions;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry registry;

    private WalSlot wal;
    private final UUID customer = UUID.randomUUID();
    private final UUID merchant = UUID.randomUUID();
    private final UUID storeA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final UUID storeB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private Placed placed;

    @BeforeEach
    void setUp() {
        placed = Sagas.place(transactions, customer, merchant, storeA, storeB);
        wal = WalSlot.create(jdbc, "order_stock_reply_it"); // after placement: only the reply effects
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private void deliver(String json) throws Exception {
        Sagas.deliver(consumers, json);
    }

    /** Outbox inserts since the last call, as (event_type, payload). */
    private List<Out> outbox() throws Exception {
        List<Out> out = new ArrayList<>();
        for (WalSlot.Change c : wal.changes()) {
            if (c.isOutbox() && "INSERT".equals(c.op())) {
                String type = c.data().replaceAll(".*event_type\\[character varying]:'([A-Za-z]+)'.*", "$1");
                out.add(new Out(type, JSON.readTree(c.payload())));
            }
        }
        return out;
    }

    private record Out(String type, JsonNode payload) {
    }

    private String status(Order o) {
        return jdbc.queryForObject("SELECT status || '/' || stock_state FROM orders WHERE id = ?", String.class,
                o.id().getValue());
    }

    private String checkoutStatus() {
        return jdbc.queryForObject("SELECT status FROM checkouts WHERE id = ?", String.class,
                placed.checkout().id().getValue());
    }

    private int slots(UUID store) {
        return count("SELECT placed_count FROM store_day_counters WHERE store_id = ?", store);
    }

    private void placeBoth() throws Exception {
        deliver(Sagas.reserved(placed.order(storeA)));
        deliver(Sagas.reserved(placed.order(storeB)));
        outbox();
    }

    @Test
    @DisplayName("both stores reserved → PLACED with numbers, Confirm per order and one CheckoutPlaced (BR-ORD-14)")
    void placesWhenAllReserved() throws Exception {
        deliver(Sagas.reserved(placed.order(storeA)));
        assertThat(checkoutStatus()).isEqualTo("PENDING"); // D3: B has not answered
        assertThat(outbox()).isEmpty();

        deliver(Sagas.reserved(placed.order(storeB)));
        assertThat(checkoutStatus()).isEqualTo("PLACED");
        assertThat(status(placed.order(storeA))).isEqualTo("PLACED/CONFIRM_SENT");
        assertThat(jdbc.queryForObject("SELECT order_number FROM orders WHERE id = ?", String.class,
                placed.order(storeA).id().getValue())).isEqualTo("HCM01-261121-001");
        List<Out> out = outbox();
        assertThat(out).extracting(Out::type).containsExactlyInAnyOrder("ConfirmStockReservation",
                "ConfirmStockReservation", "CheckoutPlaced");
        for (Out o : out) {
            assertThat(ContractSchemaFiles.validate(o.type(), o.payload())).as(o.type()).isEmpty();
        }
        JsonNode placedEvent = out.stream().filter(o -> o.type().equals("CheckoutPlaced")).findFirst().orElseThrow()
                .payload();
        assertThat(placedEvent.get("causationId").asText())
                .isEqualTo(placed.checkout().causationId().getValue().toString());
        assertThat(placedEvent.get("orders")).hasSize(2);
        assertThat(count("SELECT count(*) FROM orders WHERE stock_due_at IS NOT NULL AND stock_attempts = 0"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a slot that cannot be given back (count already 0): ERROR + metric, the reject still commits in full")
    void giveBackMissed() throws Exception {
        jdbc.update("UPDATE store_day_counters SET placed_count = 0 WHERE store_id = ?", storeA);
        var missed = registry.find("order.saga.counter.giveback.missed").counter();
        double before = missed == null ? 0 : missed.count();

        deliver(Sagas.reserved(placed.order(storeA)));
        deliver(Sagas.rejected(placed.order(storeB), null, "INSUFFICIENT_STOCK"));

        assertThat(registry.find("order.saga.counter.giveback.missed").counter().count()).isEqualTo(before + 1);
        assertThat(checkoutStatus()).isEqualTo("REJECTED");
        assertThat(status(placed.order(storeA))).isEqualTo("REJECTED/RELEASE_SENT");
        assertThat(slots(storeA)).isZero();
        assertThat(slots(storeB)).isZero();
        assertThat(outbox()).extracting(Out::type)
                .containsExactlyInAnyOrder("ReleaseStockReservation", "CheckoutRejected");
    }

    @Test
    @DisplayName("A reserved, B rejected → REJECTED with B's line reason, Release for A only, both slots given back")
    void rejectsWhenOneRefuses() throws Exception {
        deliver(Sagas.reserved(placed.order(storeA)));
        deliver(Sagas.rejected(placed.order(storeB), null, "INSUFFICIENT_STOCK"));

        assertThat(checkoutStatus()).isEqualTo("REJECTED");
        assertThat(status(placed.order(storeA))).isEqualTo("REJECTED/RELEASE_SENT");
        assertThat(status(placed.order(storeB))).isEqualTo("REJECTED/RESERVE_REJECTED");
        assertThat(slots(storeA)).isZero();
        assertThat(slots(storeB)).isZero();
        List<Out> out = outbox();
        assertThat(out).extracting(Out::type).containsExactlyInAnyOrder("ReleaseStockReservation", "CheckoutRejected");
        JsonNode release = out.stream().filter(o -> o.type().equals("ReleaseStockReservation")).findFirst()
                .orElseThrow().payload();
        assertThat(release.get("orderId").asText()).isEqualTo(placed.order(storeA).id().getValue().toString());
        assertThat(release.get("restock").asBoolean()).isTrue();
        assertThat(release.get("reason").asText()).isEqualTo("CHECKOUT_REJECTED");
        JsonNode rejected = out.stream().filter(o -> o.type().equals("CheckoutRejected")).findFirst().orElseThrow()
                .payload();
        assertThat(ContractSchemaFiles.validate("CheckoutRejected", rejected)).isEmpty();
        assertThat(rejected.get("reasons")).singleElement().satisfies(r -> {
            assertThat(r.get("code").asText()).isEqualTo("INSUFFICIENT_STOCK");
            assertThat(r.get("storeId").asText()).isEqualTo(storeB.toString());
            assertThat(r.get("sku").asText()).isEqualTo("TS-M");
        });
        assertThat(rejected.get("checkoutId").asText()).isEqualTo(placed.checkout().id().getValue().toString());
    }

    @Test
    @DisplayName("every reply redelivered or re-published with new ids: no row, outbox or history change")
    void repeatedReplies() throws Exception {
        String reservedA = Sagas.reserved(placed.order(storeA));
        String reservedB = Sagas.reserved(placed.order(storeB));
        deliver(reservedA);
        deliver(reservedB);
        outbox();
        int history = count("SELECT count(*) FROM order_transitions");
        deliver(reservedA);                                  // redelivered
        deliver(Sagas.reserved(placed.order(storeB)));       // re-published, new eventId and causationId
        deliver(Sagas.confirmed(placed.order(storeA)));
        String confirmedA = Sagas.confirmed(placed.order(storeA));
        deliver(confirmedA);
        assertThat(outbox()).isEmpty();
        assertThat(count("SELECT count(*) FROM order_transitions")).isEqualTo(history);
        assertThat(status(placed.order(storeA))).isEqualTo("PLACED/CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT stock_due_at IS NULL FROM orders WHERE id = ?", Boolean.class,
                placed.order(storeA).id().getValue())).isTrue();
    }

    @Test
    @DisplayName("ConfirmationFailed → CANCELLED, RELEASE_SENT with a due time, slot back, Release; after CONFIRMED: no change")
    void confirmationFailed() throws Exception {
        placeBoth();
        deliver(Sagas.confirmed(placed.order(storeA)));
        deliver(Sagas.confirmationFailed(placed.order(storeA), "ORDER_CLOSED")); // after CONFIRMED
        assertThat(status(placed.order(storeA))).isEqualTo("PLACED/CONFIRMED");
        assertThat(outbox()).isEmpty();

        deliver(Sagas.confirmationFailed(placed.order(storeB), "NOT_RESERVED"));
        assertThat(status(placed.order(storeB))).isEqualTo("CANCELLED/RELEASE_SENT");
        assertThat(jdbc.queryForObject("SELECT stock_due_at IS NOT NULL FROM orders WHERE id = ?", Boolean.class,
                placed.order(storeB).id().getValue())).isTrue();
        assertThat(slots(storeB)).isZero();
        assertThat(slots(storeA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM order_transitions WHERE order_id = ? AND to_status = 'CANCELLED' "
                + "AND reason = 'STOCK_NOT_CONFIRMED'", placed.order(storeB).id().getValue())).isEqualTo(1);
        assertThat(outbox()).singleElement().satisfies(o -> {
            assertThat(o.type()).isEqualTo("ReleaseStockReservation");
            assertThat(o.payload().get("reason").asText()).isEqualTo("STOCK_NOT_CONFIRMED");
        });
        assertThat(checkoutStatus()).isEqualTo("PLACED"); // the checkout stays placed; only B is cancelled
    }

    @Test
    @DisplayName("out of order: Released → RELEASED; then Rejected{ORDER_CLOSED} and StockReserved change nothing")
    void outOfOrder() throws Exception {
        deliver(Sagas.reserved(placed.order(storeA)));
        deliver(Sagas.rejected(placed.order(storeB), "STALE_COMMAND", null));
        outbox();
        assertThat(status(placed.order(storeA))).isEqualTo("REJECTED/RELEASE_SENT");

        deliver(Sagas.reserved(placed.order(storeA)));                       // on RELEASE_SENT
        deliver(Sagas.released(placed.order(storeA)));
        assertThat(status(placed.order(storeA))).isEqualTo("REJECTED/RELEASED");
        assertThat(jdbc.queryForObject("SELECT stock_due_at IS NULL FROM orders WHERE id = ?", Boolean.class,
                placed.order(storeA).id().getValue())).isTrue();
        deliver(Sagas.rejected(placed.order(storeA), "ORDER_CLOSED", null)); // after Released
        deliver(Sagas.reserved(placed.order(storeA)));                       // on RELEASED
        deliver(Sagas.released(placed.order(storeB)));                       // B never had a Release
        assertThat(status(placed.order(storeA))).isEqualTo("REJECTED/RELEASED");
        assertThat(status(placed.order(storeB))).isEqualTo("REJECTED/RESERVE_REJECTED");
        assertThat(outbox()).isEmpty();
    }

    @Test
    @DisplayName("a reply that does not belong: wrong merchant, unknown order, or lines not the order's → exception, nothing written")
    void invalid() {
        Order a = placed.order(storeA);
        String otherMerchant = Sagas.reserved(a).replace(merchant.toString(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> deliver(otherMerchant)).isInstanceOf(InvalidStockReplyException.class);
        String unknownOrder = Sagas.released(a).replace(a.id().getValue().toString(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> deliver(unknownOrder)).isInstanceOf(InvalidStockReplyException.class);
        String wrongQuantity = Sagas.reserved(a).replace("\"quantity\":2", "\"quantity\":3");
        assertThatThrownBy(() -> deliver(wrongQuantity)).isInstanceOf(InvalidStockReplyException.class);
        assertThat(status(a)).isEqualTo("PENDING/RESERVE_SENT");
        assertThat(count("SELECT count(*) FROM order_transitions WHERE from_status IS NOT NULL")).isZero();
    }
}
