package com.dotran.oms.order.infrastructure.client;

import com.dotran.oms.order.application.client.OrderQuoteClient;
import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.infrastructure.client.dto.OrderQuoteRequest;
import com.dotran.oms.order.infrastructure.client.dto.OrderQuoteResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * stores-service's internal order-quote ({@code POST /internal/v1/store/merchants/{merchantId}/stores/{storeId}/order-quote}).
 * Built on the injected {@code RestClient.Builder}, so oms-core sends the MDC requestId as X-Request-Id. Spring 6.1 has
 * no per-request timeout: each call gets its own {@link SimpleClientHttpRequestFactory} with connect = min(connect-timeout,
 * time left) and read = min(read-timeout, time left). Its read timeout is the socket timeout, so it bounds every read,
 * the response body included (the JDK HttpClient factory's timeout ends when the headers arrive, so a body that stalls
 * after them would block the listener without a limit). One retry on a connection failure or a 5xx, if time is left.
 */
@Component
@Slf4j
public class StoresOrderQuoteClient implements OrderQuoteClient {

    static final String PATH = "/internal/v1/store/merchants/{merchantId}/stores/{storeId}/order-quote";
    static final Duration MIN_CALL_TIME = Duration.ofMillis(100);

    private final RestClient.Builder builder;
    private final StoresClientProperties properties;

    public StoresOrderQuoteClient(RestClient.Builder builder, StoresClientProperties properties) {
        this.builder = builder;
        this.properties = properties;
    }

    @Override
    public QuoteResult quote(UUID merchantId, UUID storeId, List<String> skus, Duration timeLeft) {
        long deadline = System.nanoTime() + timeLeft.toNanos();
        Attempt first = attempt(merchantId, storeId, skus, left(deadline));
        if (!first.retry() || left(deadline).compareTo(MIN_CALL_TIME) < 0) {
            return first.result();
        }
        return attempt(merchantId, storeId, skus, left(deadline)).result();
    }

    private Attempt attempt(UUID merchantId, UUID storeId, List<String> skus, Duration timeLeft) {
        if (timeLeft.compareTo(MIN_CALL_TIME) < 0) {
            return new Attempt(new QuoteResult.Unavailable("no time left"), false);
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(min(properties.connectTimeout(), timeLeft));
        factory.setReadTimeout(min(properties.readTimeout(), timeLeft));
        RestClient client = builder.clone().baseUrl(properties.baseUrl()).requestFactory(factory).build();
        try {
            return client.post()
                    .uri(PATH, merchantId, storeId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OrderQuoteRequest(skus))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 404) {
                            return new Attempt(new QuoteResult.NotFound(), false);
                        }
                        if (status.value() == 400) {
                            log.error("order-quote answered 400 for storeId={}: our request is wrong", storeId);
                            return new Attempt(new QuoteResult.BadRequest(), false);
                        }
                        if (status.is5xxServerError()) {
                            return new Attempt(new QuoteResult.Unavailable("HTTP " + status.value()), true);
                        }
                        if (!status.is2xxSuccessful()) {
                            log.error("order-quote answered {} for storeId={}", status.value(), storeId);
                            return new Attempt(new QuoteResult.Unavailable("HTTP " + status.value()), false);
                        }
                        OrderQuoteResponse body = response.bodyTo(OrderQuoteResponse.class);
                        return new Attempt(toResult(body, storeId), false);
                    });
        } catch (ResourceAccessException e) {
            boolean connect = isConnectFailure(e);
            return new Attempt(new QuoteResult.Unavailable(connect ? "connection failed"
                    : isTimeout(e) ? "read timeout" : "I/O error"), connect);
        } catch (RestClientException | IllegalArgumentException e) {
            log.error("order-quote answer unreadable for storeId={}: {}", storeId, e.getClass().getSimpleName());
            return new Attempt(new QuoteResult.Unavailable("unreadable answer"), false);
        }
    }

    private static QuoteResult toResult(OrderQuoteResponse body, UUID storeId) {
        if (body == null || body.open() == null || body.store() == null) {
            log.error("order-quote answer without store/open for storeId={}", storeId);
            return new QuoteResult.Unavailable("unreadable answer");
        }
        List<OrderQuote.SkuQuote> skus = body.skus() == null ? List.of() : body.skus().stream()
                .map(s -> new OrderQuote.SkuQuote(s.sku(), s.found(), s.productId(), s.status(), s.productStatus(),
                        s.currency(), s.productName(), s.skuName(), s.price()))
                .toList();
        return new QuoteResult.Ok(new OrderQuote(body.merchantStatus(), body.store().currency(), body.open(),
                body.reasons(), skus, body.store().code(), body.store().timeZone(), body.localDate(),
                body.store().maxOrdersPerDay()));
    }

    /** Refused, no route, or the connect timeout (a SocketTimeoutException whose message names the connect). */
    private static boolean isConnectFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
                return true;
            }
            if (cause instanceof SocketTimeoutException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
        }
        return false;
    }

    /** A read (socket) timeout, headers or body. */
    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static Duration left(long deadlineNanos) {
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private record Attempt(QuoteResult result, boolean retry) {
    }
}
