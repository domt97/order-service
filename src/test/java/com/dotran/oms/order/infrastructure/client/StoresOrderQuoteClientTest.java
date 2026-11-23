package com.dotran.oms.order.infrastructure.client;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.core.rest.client.RequestIdClientConfig;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real local HTTP server (MockRestServiceServer replaces the request factory, so it cannot prove timeouts).
 * The store id in the path picks the server's behaviour.
 */
@DisplayName("StoresOrderQuoteClient")
class StoresOrderQuoteClientTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final String OK_BODY = """
            {"store":{"id":"%s","code":"HCM01","status":"ACTIVE","currency":"VND","timeZone":"Asia/Ho_Chi_Minh",
                      "maxOrdersPerDay":40},
             "merchantStatus":"ACTIVE","now":"2026-11-16T03:00:00Z","localDate":"2026-11-16","open":true,
             "reasons":[],"closedWindowTypes":[],
             "skus":[{"sku":"TS-M","found":true,"productId":"%s","productName":"Tee","skuName":"M","price":25000,
                      "currency":"VND","status":"ACTIVE","productStatus":"ACTIVE"}]}
            """;

    private HttpServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final Map<String, String> requestIds = new ConcurrentHashMap<>();
    private final UUID product = UUID.randomUUID();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String[] path = exchange.getRequestURI().getPath().split("/");
            String store = path[path.length - 2];
            int n = calls.computeIfAbsent(store, k -> new AtomicInteger()).incrementAndGet();
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            if (requestId != null) {
                requestIds.put(store, requestId);
            }
            exchange.getRequestBody().readAllBytes();
            int status;
            String body = "";
            switch (behaviour(store)) {
                case "404" -> status = 404;
                case "400" -> status = 400;
                case "401" -> status = 401;
                case "503" -> status = 503;
                case "503then200" -> {
                    status = n == 1 ? 503 : 200;
                    body = n == 1 ? "" : OK_BODY.formatted(store, product);
                }
                case "garbage" -> {
                    status = 200;
                    body = "not json";
                }
                case "bad-date" -> {
                    status = 200;
                    body = OK_BODY.formatted(store, product).replace("\"2026-11-16\"", "\"16/11/2026\"");
                }
                case "stall-body" -> {
                    // Headers and the first bytes at once, then nothing: only a per-read timeout ends this.
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("{\"store\":".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    sleep(3_000);
                    exchange.close();
                    return;
                }
                case "slow" -> {
                    sleep(2_000);
                    status = 200;
                    body = OK_BODY.formatted(store, product);
                }
                default -> {
                    status = 200;
                    body = OK_BODY.formatted(store, product);
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private final Map<String, String> behaviours = new ConcurrentHashMap<>();

    private String behaviour(String store) {
        return behaviours.getOrDefault(store, "ok");
    }

    private UUID storeWith(String behaviour) {
        UUID store = UUID.randomUUID();
        behaviours.put(store.toString(), behaviour);
        return store;
    }

    private StoresOrderQuoteClient client(Duration readTimeout) {
        return client("http://127.0.0.1:" + server.getAddress().getPort(), readTimeout);
    }

    private static StoresOrderQuoteClient client(String baseUrl, Duration readTimeout) {
        RestClient.Builder builder = RestClient.builder();
        new RequestIdClientConfig().requestIdRestClientCustomizer().customize(builder);
        return new StoresOrderQuoteClient(builder, new StoresClientProperties(baseUrl, Duration.ofSeconds(1),
                readTimeout));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("200 → Ok with the store currency and SKUs; X-Request-Id from the MDC is sent")
    void ok() {
        UUID store = storeWith("ok");
        String requestId = UUID.randomUUID().toString();
        QuoteResult result;
        try (MdcScope mdc = MdcScope.open().put(MdcKeys.REQUEST_ID, requestId)) {
            result = client(Duration.ofSeconds(3)).quote(MERCHANT, store, List.of("TS-M"), Duration.ofSeconds(20));
        }
        assertThat(result).isInstanceOfSatisfying(QuoteResult.Ok.class, ok -> {
            assertThat(ok.quote().storeCurrency()).isEqualTo("VND");
            assertThat(ok.quote().open()).isTrue();
            assertThat(ok.quote().storeCode()).isEqualTo("HCM01");
            assertThat(ok.quote().timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
            assertThat(ok.quote().localDate()).isEqualTo(java.time.LocalDate.of(2026, 11, 16));
            assertThat(ok.quote().maxOrdersPerDay()).isEqualTo(40);
            assertThat(ok.quote().skus()).singleElement().satisfies(s -> {
                assertThat(s.productId()).isEqualTo(product);
                assertThat(s.productName()).isEqualTo("Tee");
                assertThat(s.skuName()).isEqualTo("M");
                assertThat(s.price()).isEqualByComparingTo("25000");
            });
        });
        assertThat(requestIds.get(store.toString())).isEqualTo(requestId);
    }

    @Test
    @DisplayName("404 → NotFound, 400 → BadRequest, 401 → Unavailable; each called once")
    void statuses() {
        StoresOrderQuoteClient client = client(Duration.ofSeconds(3));
        UUID notFound = storeWith("404");
        UUID bad = storeWith("400");
        UUID unauthorized = storeWith("401");
        assertThat(client.quote(MERCHANT, notFound, List.of(), Duration.ofSeconds(20))).isInstanceOf(QuoteResult.NotFound.class);
        assertThat(client.quote(MERCHANT, bad, List.of(), Duration.ofSeconds(20))).isInstanceOf(QuoteResult.BadRequest.class);
        assertThat(client.quote(MERCHANT, unauthorized, List.of(), Duration.ofSeconds(20)))
                .isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(List.of(notFound, bad, unauthorized)).allSatisfy(s -> assertThat(calls.get(s.toString()).get()).isEqualTo(1));
    }

    @Test
    @DisplayName("503 then 200 → one retry, Ok; 503 twice → Unavailable after 2 calls")
    void retryOn5xx() {
        StoresOrderQuoteClient client = client(Duration.ofSeconds(3));
        UUID flaky = storeWith("503then200");
        UUID down = storeWith("503");
        assertThat(client.quote(MERCHANT, flaky, List.of("TS-M"), Duration.ofSeconds(20))).isInstanceOf(QuoteResult.Ok.class);
        assertThat(calls.get(flaky.toString()).get()).isEqualTo(2);
        assertThat(client.quote(MERCHANT, down, List.of(), Duration.ofSeconds(20))).isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(calls.get(down.toString()).get()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unreadable 200 → Unavailable, not retried")
    void garbage() {
        UUID store = storeWith("garbage");
        assertThat(client(Duration.ofSeconds(3)).quote(MERCHANT, store, List.of(), Duration.ofSeconds(20)))
                .isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(calls.get(store.toString()).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed localDate → Unavailable (STORE_UNAVAILABLE), called once")
    void badLocalDate() {
        UUID store = storeWith("bad-date");
        assertThat(client(Duration.ofSeconds(3)).quote(MERCHANT, store, List.of("TS-M"), Duration.ofSeconds(20)))
                .isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(calls.get(store.toString()).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("read timeout → Unavailable, not retried")
    void readTimeout() {
        UUID store = storeWith("slow");
        long start = System.nanoTime();
        QuoteResult result = client(Duration.ofMillis(300)).quote(MERCHANT, store, List.of(), Duration.ofSeconds(20));
        assertThat(result).isInstanceOfSatisfying(QuoteResult.Unavailable.class,
                u -> assertThat(u.why()).isEqualTo("read timeout"));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
        assertThat(calls.get(store.toString()).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("headers arrive, then the body stalls: the read timeout still ends the call (not only until headers)")
    void bodyStalls() {
        UUID store = storeWith("stall-body");
        long start = System.nanoTime();
        QuoteResult result = client(Duration.ofMillis(300)).quote(MERCHANT, store, List.of(), Duration.ofSeconds(20));
        assertThat(result).isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
        assertThat(calls.get(store.toString()).get()).isEqualTo(1);
    }

    @Test
    @DisplayName("the read timeout is capped by the time left: 3 s configured, 300 ms left → answer within ~300 ms")
    void cappedByTimeLeft() {
        UUID store = storeWith("slow");
        long start = System.nanoTime();
        QuoteResult result = client(Duration.ofSeconds(3)).quote(MERCHANT, store, List.of(), Duration.ofMillis(300));
        assertThat(result).isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    @DisplayName("less than 100 ms left → no call at all")
    void noTimeLeft() {
        UUID store = storeWith("ok");
        assertThat(client(Duration.ofSeconds(3)).quote(MERCHANT, store, List.of(), Duration.ofMillis(50)))
                .isInstanceOf(QuoteResult.Unavailable.class);
        assertThat(calls.get(store.toString())).isNull();
    }

    @Test
    @DisplayName("connection refused → Unavailable (after one retry)")
    void connectionRefused() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        assertThat(client("http://127.0.0.1:" + port, Duration.ofSeconds(3))
                .quote(MERCHANT, UUID.randomUUID(), List.of(), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(QuoteResult.Unavailable.class,
                        u -> assertThat(u.why()).isEqualTo("connection failed"));
    }
}
