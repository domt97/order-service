package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.order.application.client.OrderQuoteClient;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.config.CheckoutProperties;
import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.service.checkout.CheckoutTransactions.PlaceResult;
import com.dotran.oms.order.application.usecase.checkout.PlaceCheckoutUseCase;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Checkout intake (ADR 0007), pre-reserve part. Order of the steps:
 * <ol>
 *   <li>the key's stored row (for this customer) answers every copy: same content → its outcome again, other content
 *       → CONTENT_MISMATCH;</li>
 *   <li>contract rules → INVALID_REQUEST;</li>
 *   <li>no row and requestedAt older than the STALE window → STALE;</li>
 *   <li>one order-quote per store (outside any transaction, within the quote budget) → BR-ORD-02 reasons;</li>
 *   <li>any reason → the row and its CheckoutRejected in one transaction; a lost insert re-reads the key;</li>
 *   <li>no reason → placement: the PENDING checkout, one order per store and one ReserveStock each, then the daily
 *       slots, in one transaction; a capped store → REJECTED with DAILY_CAP_REACHED; a lost insert re-reads the key.
 *       Inventory's replies decide the checkout (PLACED or REJECTED) later.</li>
 * </ol>
 * Not {@code @Transactional}: the quote calls must not hold a connection; {@link CheckoutTransactions} writes.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
public class PlaceCheckoutService implements PlaceCheckoutUseCase {

    /** No call starts with less than this left of the budget. */
    static final Duration MIN_CALL_TIME = Duration.ofMillis(100);

    private final CheckoutRepository checkoutRepository;
    private final CheckoutTransactions transactions;
    private final PlaceCheckoutValidator validator;
    private final QuoteReasonMapper quoteReasonMapper;
    private final OrderQuoteClient orderQuoteClient;
    private final CheckoutReplyFactory replies;
    private final PlacementPlanner planner;
    private final CheckoutProperties properties;
    private final StockSagaProperties stockProperties;
    private final Clock clock;

    @Override
    public PlaceCheckoutOutcome place(PlaceCheckoutCmd cmd) {
        Instant now = clock.instant();
        String hash = ContentHash.of(cmd);

        Optional<Checkout> existing = checkoutRepository.findByKey(cmd.cartId(), cmd.cartVersion(), cmd.customerId());
        if (existing.isPresent()) {
            return resolveExisting(existing.get(), cmd, hash);
        }

        List<RejectionReason> reasons = validator.validate(cmd, now);
        CheckoutStatus status = CheckoutStatus.REJECTED;
        if (reasons.isEmpty() && cmd.requestedAt().isBefore(now.minus(properties.staleAfter()))) {
            status = CheckoutStatus.STALE;
            reasons = List.of(RejectionReason.checkout(RejectionCode.STALE));
        }
        if (reasons.isEmpty()) {
            Quoted quoted = quoteAll(cmd);
            reasons = quoted.reasons();
            if (reasons.isEmpty()) {
                try {
                    return startPlacement(cmd, hash, quoted.quotes(), now);
                } catch (DailyCapReached e) {
                    reasons = e.storeIds().stream()
                            .map(store -> RejectionReason.store(RejectionCode.DAILY_CAP_REACHED, store)).toList();
                }
            }
        }

        Checkout candidate = Checkout.rejected(status, cmd.customerId(), cmd.cartId(), cmd.cartVersion(), hash,
                reasons, cmd.eventId(), cmd.requestedAt(), now);
        if (transactions.storeOutcome(candidate, replies.rejected(cmd, candidate.id(), candidate.reasons(), now))) {
            log.info("Checkout rejected: cartId={} cartVersion={} checkoutId={} status={} codes={}",
                    cmd.cartId().getValue(), cmd.cartVersion(), candidate.id().getValue(), status,
                    candidate.reasons().stream().map(r -> r.code().name()).distinct().toList());
            return PlaceCheckoutOutcome.REJECTED_STORED;
        }
        // Another copy stored the key first (or the key is another customer's): re-read for this customer.
        return checkoutRepository.findByKey(cmd.cartId(), cmd.cartVersion(), cmd.customerId())
                .map(row -> resolveExisting(row, cmd, hash))
                .orElseGet(() -> contentMismatch(cmd));
    }

    private PlaceCheckoutOutcome startPlacement(PlaceCheckoutCmd cmd, String hash, Map<UUID, OrderQuote> quotes,
                                                Instant now) {
        Checkout pending = Checkout.pending(cmd.customerId(), cmd.cartId(), cmd.cartVersion(), hash, cmd.eventId(),
                cmd.requestedAt(), now, now.plus(stockProperties.reserveTimeout()));
        PlaceResult result = transactions.place(planner.plan(pending, cmd, quotes, now), now);
        if (result == PlaceResult.PLACEMENT_STARTED) {
            log.info("Checkout placement started: cartId={} cartVersion={} checkoutId={} stores={}",
                    cmd.cartId().getValue(), cmd.cartVersion(), pending.id().getValue(), cmd.stores().size());
            return PlaceCheckoutOutcome.PLACEMENT_STARTED;
        }
        // Another copy stored the key first (or the key is another customer's): re-read for this customer.
        return checkoutRepository.findByKey(cmd.cartId(), cmd.cartVersion(), cmd.customerId())
                .map(row -> resolveExisting(row, cmd, hash))
                .orElseGet(() -> contentMismatch(cmd));
    }

    private PlaceCheckoutOutcome resolveExisting(Checkout row, PlaceCheckoutCmd cmd, String hash) {
        if (!row.sameContent(hash)) {
            return contentMismatch(cmd);
        }
        if (row.isPlaced()) {
            transactions.republishPlaced(row, cmd.eventId(), clock.instant());
            log.info("CheckoutPlaced sent again: cartId={} cartVersion={} checkoutId={}",
                    cmd.cartId().getValue(), cmd.cartVersion(), row.id().getValue());
            return PlaceCheckoutOutcome.REPUBLISHED;
        }
        if (!row.answersWithRejection()) {
            // PENDING: inventory's replies (or the reserve timeout) decide it and send the reply then.
            log.info("Checkout key already {}: cartId={} cartVersion={} checkoutId={}; nothing sent",
                    row.status(), cmd.cartId().getValue(), cmd.cartVersion(), row.id().getValue());
            return PlaceCheckoutOutcome.IN_PROGRESS;
        }
        transactions.publish(replies.rejected(cmd, row.id(), row.reasons(), clock.instant()));
        log.info("Checkout outcome sent again: cartId={} cartVersion={} checkoutId={} status={}",
                cmd.cartId().getValue(), cmd.cartVersion(), row.id().getValue(), row.status());
        return PlaceCheckoutOutcome.REPUBLISHED;
    }

    private PlaceCheckoutOutcome contentMismatch(PlaceCheckoutCmd cmd) {
        transactions.publish(replies.contentMismatch(cmd, clock.instant()));
        log.warn("Checkout key reused with other content: cartId={} cartVersion={}; CONTENT_MISMATCH sent",
                cmd.cartId().getValue(), cmd.cartVersion());
        return PlaceCheckoutOutcome.CONTENT_MISMATCH;
    }

    /** Every store's reasons, and the quotes that answered Ok (placement snapshots them). */
    private Quoted quoteAll(PlaceCheckoutCmd cmd) {
        Instant deadline = clock.instant().plus(properties.quoteBudget());
        List<RejectionReason> reasons = new ArrayList<>();
        Map<UUID, OrderQuote> quotes = new HashMap<>();
        for (PlaceCheckoutCmd.Store store : cmd.stores()) {
            Duration left = Duration.between(clock.instant(), deadline);
            QuoteResult result = left.compareTo(MIN_CALL_TIME) < 0
                    ? new QuoteResult.Unavailable("quote budget used up")
                    : orderQuoteClient.quote(store.merchantId(), store.storeId(),
                            store.lines().stream().map(PlaceCheckoutCmd.Line::sku).toList(), left);
            if (result instanceof QuoteResult.NotFound) {
                // Stores are never deleted: the command carries a wrong merchant/store pair (integration.md: alert).
                log.error("order-quote 404: merchantId={} storeId={} not found for this merchant; INVALID_REQUEST",
                        store.merchantId(), store.storeId());
            } else if (result instanceof QuoteResult.Unavailable unavailable) {
                log.warn("order-quote unavailable: merchantId={} storeId={} ({}); STORE_UNAVAILABLE",
                        store.merchantId(), store.storeId(), unavailable.why());
            }
            if (result instanceof QuoteResult.Ok ok) {
                quotes.put(store.storeId(), ok.quote());
            }
            reasons.addAll(quoteReasonMapper.reasons(store, result));
        }
        return new Quoted(RejectionReason.normalise(reasons), quotes);
    }

    private record Quoted(List<RejectionReason> reasons, Map<UUID, OrderQuote> quotes) {
    }
}
