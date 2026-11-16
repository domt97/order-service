package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.support.ContractSchemaFiles;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.WalSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listener method on the real services and Postgres (no broker): JSON record in → checkout row and the reply as
 * Debezium would read it from the WAL. The order-quote client is a stub.
 */
class PlaceCheckoutConsumerIT extends PostgresIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-00000000d001");

    @Autowired
    private PlaceCheckoutConsumer consumer;

    private WalSlot wal;

    @BeforeEach
    void slot() {
        wal = WalSlot.create(jdbc, "order_consumer_it");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private static String message(UUID cart, String sku) {
        return """
                {"eventId":"%s","eventType":"PlaceCheckout","occurredAt":"%s",
                 "customerId":"00000000-0000-0000-0000-00000000c001","cartId":"%s","cartVersion":3,"requestedAt":"%s",
                 "delivery":{"address":{"line1":"1 Le Loi","city":"Ho Chi Minh","country":"VN"},
                             "recipientName":"Nguyen Van A","phone":"0901234567"},
                 "stores":[{"merchantId":"00000000-0000-0000-0000-00000000a001","storeId":"%s",
                            "quoteId":"00000000-0000-0000-0000-0000000e0001",
                            "lines":[{"lineId":"00000000-0000-0000-0000-0000000f0001","productId":"%s",
                                      "sku":%s,"quantity":1}]}]}
                """.formatted(UUID.randomUUID(), Instant.now(), cart, Instant.now(), STORE, PRODUCT,
                JSON.valueToTree(sku).toString());
    }

    private void consume(UUID cart, String value) throws Exception {
        consumer.consume(new ConsumerRecord<>("PlaceCheckout", 0, 0L, cart.toString(), value));
    }

    private static QuoteResult closed() {
        return new QuoteResult.Ok(new OrderQuote("ACTIVE", "VND", false, List.of("OUTSIDE_HOURS"),
                List.of(new OrderQuote.SkuQuote("TS-M", true, PRODUCT, "ACTIVE", "ACTIVE", "VND"))));
    }

    private List<JsonNode> replies() throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (WalSlot.Change change : wal.changes()) {
            if (change.isOutbox() && "INSERT".equals(change.op())) {
                assertThat(change.data()).contains("event_type[character varying]:'CheckoutRejected'");
                out.add(JSON.readTree(change.payload()));
            }
        }
        return out;
    }

    @Test
    @DisplayName("closed store → REJECTED row + one CheckoutRejected keyed by cartId; a copy → same reasons, new eventId")
    void rejectedThenRepublished() throws Exception {
        quotes = new StubQuotes(store -> closed());
        UUID cart = UUID.randomUUID();
        String value = message(cart, "TS-M");
        consume(cart, value);
        consume(cart, value.replaceFirst("\"eventId\":\"[0-9a-f-]+\"", "\"eventId\":\"" + UUID.randomUUID() + "\""));

        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ? AND status = 'REJECTED'", cart)).isEqualTo(1);
        List<WalSlot.Change> inserts = wal.changes().stream()
                .filter(c -> c.isOutbox() && "INSERT".equals(c.op())).toList();
        assertThat(inserts).hasSize(2);
        assertThat(inserts).allSatisfy(c -> assertThat(c.data()).contains("aggregate_id[uuid]:'" + cart + "'"));
        JsonNode first = JSON.readTree(inserts.get(0).payload());
        JsonNode second = JSON.readTree(inserts.get(1).payload());
        assertThat(ContractSchemaFiles.validate("CheckoutRejected", first)).isEmpty();
        assertThat(second.get("reasons")).isEqualTo(first.get("reasons"));
        assertThat(second.get("checkoutId")).isEqualTo(first.get("checkoutId"));
        assertThat(second.get("eventId")).isNotEqualTo(first.get("eventId"));
        assertThat(first.get("reasons").get(0).get("code").asText()).isEqualTo("STORE_CLOSED");
    }

    @Test
    @DisplayName("an open store and a matching SKU → nothing stored, nothing sent (placement not built)")
    void passedNotPlaced() throws Exception {
        quotes = new StubQuotes(store -> new QuoteResult.Ok(new OrderQuote("ACTIVE", "VND", true, List.of(),
                List.of(new OrderQuote.SkuQuote("TS-M", true, PRODUCT, "ACTIVE", "ACTIVE", "VND")))));
        UUID cart = UUID.randomUUID();
        consume(cart, message(cart, "TS-M"));
        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ?", cart)).isZero();
        assertThat(replies()).isEmpty();
    }

    @Test
    @DisplayName("a SKU with NUL → INVALID_REQUEST stored and published (no JSONB error), the SKU text not in it")
    void nulSku() throws Exception {
        UUID cart = UUID.randomUUID();
        consume(cart, message(cart, "TS\u0000M"));
        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ?", cart)).isEqualTo(1);
        List<JsonNode> replies = replies();
        assertThat(replies).singleElement().satisfies(r -> {
            assertThat(r.get("reasons").get(0).get("code").asText()).isEqualTo("INVALID_REQUEST");
            assertThat(r.get("reasons").get(0).has("sku")).isFalse();
        });
    }

    @Test
    @DisplayName("two copies at once, same content: one row, two replies with the same reasons and checkoutId")
    void raceSameContent() throws Exception {
        CountDownLatch bothQuoting = new CountDownLatch(2);
        quotes = new StubQuotes(store -> {
            bothQuoting.countDown();
            await(bothQuoting);
            return closed();
        });
        UUID cart = UUID.randomUUID();
        String value = message(cart, "TS-M");
        runTogether(() -> consume(cart, value), () -> consume(cart, value));

        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ?", cart)).isEqualTo(1);
        List<JsonNode> replies = replies();
        assertThat(replies).hasSize(2);
        assertThat(replies.get(1).get("reasons")).isEqualTo(replies.get(0).get("reasons"));
        assertThat(replies.get(1).get("checkoutId")).isEqualTo(replies.get(0).get("checkoutId"));
    }

    @Test
    @DisplayName("two copies at once, different content: one row; the loser gets CONTENT_MISMATCH without checkoutId")
    void raceOtherContent() throws Exception {
        CountDownLatch bothQuoting = new CountDownLatch(2);
        quotes = new StubQuotes(store -> {
            bothQuoting.countDown();
            await(bothQuoting);
            return closed();
        });
        UUID cart = UUID.randomUUID();
        runTogether(() -> consume(cart, message(cart, "TS-M")), () -> consume(cart, message(cart, "TS-L")));

        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ?", cart)).isEqualTo(1);
        List<JsonNode> replies = replies();
        assertThat(replies).hasSize(2);
        assertThat(replies).filteredOn(r -> "CONTENT_MISMATCH".equals(r.get("reasons").get(0).get("code").asText()))
                .singleElement().satisfies(r -> assertThat(r.has("checkoutId")).isFalse());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private interface Task {
        void run() throws Exception;
    }

    private static void runTogether(Task a, Task b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> {
                a.run();
                return null;
            });
            Future<?> fb = pool.submit(() -> {
                b.run();
                return null;
            });
            fa.get(30, TimeUnit.SECONDS);
            fb.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
