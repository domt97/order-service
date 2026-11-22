package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.logging.LogSanitizer;
import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.event.StockCommandFactory;
import com.dotran.oms.order.application.metrics.SagaMetrics;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.repository.OrderRepository;
import com.dotran.oms.order.application.repository.OrderRepository.OrderKey;
import com.dotran.oms.order.application.repository.OrderRepository.Rescheduled;
import com.dotran.oms.order.application.repository.OrderTransition;
import com.dotran.oms.order.application.repository.OutboxEventRepository;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository;
import com.dotran.oms.order.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.CheckoutRef;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.OrderRef;
import com.dotran.oms.order.application.usecase.stock.HandleStockReplyUseCase.Outcome;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.OrderNumber;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The stock saga's write transactions (ADR 0006, ADR 0007), in their own bean so the proxy applies
 * {@code @Transactional}. One transaction per reply: lock the checkout, then its orders in id order, check the reply
 * against the locked order, move the order out of the one state the reply answers, and decide the checkout in the same
 * transaction. Lock order everywhere: checkouts → orders (id order) → store_day_counters (store id order). Lock 2 s,
 * statement 3 s, transaction 10 s (5 s for the defer writes and the gauge read). The two jobs call the methods below
 * one row (or one batch) per transaction, so SKIP LOCKED splits the work between instances.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StockSagaTransactions {

    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(3);
    static final int TRANSACTION_TIMEOUT_SECONDS = 10;
    static final int DEFER_TIMEOUT_SECONDS = 5;
    /** A checkout whose timeout step failed is tried again after this. */
    static final Duration RESERVE_RETRY = Duration.ofSeconds(60);

    static final String CHECKOUT_REJECTED = "CHECKOUT_REJECTED";
    static final String RESERVE_TIMEOUT = "RESERVE_TIMEOUT";
    static final String STOCK_NOT_CONFIRMED = "STOCK_NOT_CONFIRMED";


    private final CheckoutRepository checkouts;
    private final OrderRepository orders;
    private final StoreDayCounterRepository counters;
    private final OutboxEventRepository outbox;
    private final TransactionTimeoutRepository timeouts;
    private final StockCommandFactory commands;
    private final CheckoutReplyFactory replies;
    private final StockSagaProperties properties;
    private final SagaMetrics metrics;
    private final Clock clock;

    /** One inventory reply, all of it in this transaction; a mismatching reply throws and writes nothing. */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Outcome apply(StockReplyCmd reply) {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        Instant now = clock.instant();
        // The ids an unlocked read gives never change; everything else is read after the locks.
        OrderKey key = orders.findScoped(reply.orderId(), reply.merchantId(), reply.storeId())
                .orElseThrow(() -> new InvalidStockReplyException("unknown order for this merchant and store"));
        Checkout checkout = checkouts.lockForUpdate(key.checkoutId(), key.customerId())
                .orElseThrow(() -> new IllegalStateException("Order without its checkout: orderId="
                        + reply.orderId().getValue()));
        List<Order> locked = orders.lockByCheckout(key.checkoutId(), key.customerId());
        Order order = locked.stream().filter(o -> o.id().equals(reply.orderId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Locked orders miss orderId=" + reply.orderId().getValue()));
        checkLines(reply, order);

        boolean moved = switch (reply.type()) {
            case RESERVED -> move(order, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.PENDING,
                    StockState.RESERVED, now, b -> b.reserveExpiresAt(reply.expiresAt()));
            case RESERVATION_REJECTED -> move(order, OrderStatus.PENDING, StockState.RESERVE_SENT, OrderStatus.PENDING,
                    StockState.RESERVE_REJECTED, now,
                    b -> b.reserveRejection(CheckoutDecision.reserveRejection(reply)));
            case CONFIRMED -> move(order, OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.PLACED,
                    StockState.CONFIRMED, now, b -> b.confirmedAt(now));
            case CONFIRMATION_FAILED -> confirmationFailed(order, reply, now);
            case RELEASED -> move(order, order.status(), StockState.RELEASE_SENT, order.status(), StockState.RELEASED,
                    now, Function.identity());
        };
        if (!moved) {
            log.info("Stock reply {} for orderId={} in {}/{}: no change (repeated or late)", reply.type(),
                    order.id().getValue(), order.status(), order.stockState());
            return Outcome.NO_CHANGE;
        }
        log.info("Stock reply {} applied: checkoutId={} orderId={} merchantId={} storeId={} causationId={}",
                reply.type(), checkout.id().getValue(), order.id().getValue(), order.merchantId().getValue(),
                order.storeId().getValue(), reply.causationId());
        if (reply.type() == StockReplyCmd.Type.RESERVED || reply.type() == StockReplyCmd.Type.RESERVATION_REJECTED) {
            decide(checkout, false, now);
        }
        return Outcome.APPLIED;
    }

    /**
     * Reserve-timeout job, one checkout (D3): the oldest PENDING checkout whose reserve deadline passed, not in
     * {@code excluded}, SKIP LOCKED; decided with unanswered reserves counted as timed out (all RESERVED still places).
     * False when none is due. A failure after the lock throws {@link StockSagaFailure} naming the checkout.
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public boolean expireOneDue(Set<UUID> excluded) {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        Instant now = clock.instant();
        Optional<Checkout> due = checkouts.lockNextDueReserve(now, excluded);
        if (due.isEmpty()) {
            return false;
        }
        Checkout checkout = due.get();
        try {
            orders.lockByCheckout(checkout.id(), checkout.customerId());
            log.info("Reserve deadline passed: checkoutId={}", checkout.id().getValue());
            decide(checkout, true, now);
        } catch (RuntimeException e) {
            throw StockSagaFailure.checkout(new CheckoutRef(checkout.id(), checkout.customerId()), e);
        }
        return true;
    }

    /**
     * Re-send job, one batch: up to {@code batchSize} orders whose Confirm or Release is due, not in {@code excluded},
     * SKIP LOCKED; each gets its command again and its next due time (ADR 0006 obligation 2). Empty when none is due.
     * A failure after the lock throws {@link StockSagaFailure} naming every order of the batch.
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public List<Resent> resendDueBatch(Set<UUID> excluded) {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        Instant now = clock.instant();
        List<Order> due = orders.lockDueStockCommands(now, properties.batchSize(), excluded);
        try {
            return resend(due, now);
        } catch (RuntimeException e) {
            throw StockSagaFailure.orders(due.stream().map(o -> new OrderRef(o.id(), o.merchantId())).toList(), e);
        }
    }

    /**
     * The row-by-row retry after a failed batch: the order again, only while still due (SKIP LOCKED); empty when it
     * was settled or taken meanwhile. A failure after the lock throws {@link StockSagaFailure} naming it.
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<Resent> resendOne(OrderRef ref) {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        Instant now = clock.instant();
        Optional<Order> due = orders.relockDue(ref.id(), ref.merchantId(), now);
        if (due.isEmpty()) {
            return Optional.empty();
        }
        try {
            return resend(List.of(due.get()), now).stream().findFirst();
        } catch (RuntimeException e) {
            throw StockSagaFailure.orders(List.of(ref), e);
        }
    }

    /** A checkout whose timeout step failed: tried again in 60 s (its created_at, and so the gauge, unchanged). */
    @Transactional(timeout = DEFER_TIMEOUT_SECONDS)
    public boolean deferReserve(CheckoutRef ref) {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        Instant now = clock.instant();
        return checkouts.deferReserve(ref.id(), ref.customerId(), now.plus(RESERVE_RETRY), now);
    }

    /**
     * An order whose re-send failed: attempts + 1 and the next interval, as after a re-send, so a command that keeps
     * failing reaches the attempt-3 ERROR. Empty when it is no longer CONFIRM_SENT or RELEASE_SENT.
     */
    @Transactional(timeout = DEFER_TIMEOUT_SECONDS)
    public Optional<Rescheduled> deferStockCommand(OrderRef ref) {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        return orders.reschedule(ref.id(), ref.merchantId(), clock.instant(), properties.resendInitial(),
                properties.resendMax());
    }

    /** The oldest-PENDING gauge's value, read at the start of each reserve-timeout run. */
    @Transactional(readOnly = true, timeout = DEFER_TIMEOUT_SECONDS)
    public Optional<Instant> oldestPendingCreatedAt() {
        timeouts.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        return checkouts.oldestPendingCreatedAt();
    }

    private List<Resent> resend(List<Order> due, Instant now) {
        if (due.isEmpty()) {
            return List.of();
        }
        List<OutboxMessage> messages = new ArrayList<>();
        List<Resent> resent = new ArrayList<>();
        for (Order o : due) {
            messages.add(switch (o.stockState()) {
                case CONFIRM_SENT -> commands.confirm(o, now);
                case RELEASE_SENT -> commands.release(o, Boolean.TRUE.equals(o.releaseRestock()), o.releaseReason(),
                        now);
                default -> throw new IllegalStateException("Not re-sendable: " + o.stockState());
            });
            Rescheduled next = orders.reschedule(o.id(), o.merchantId(), now, properties.resendInitial(),
                    properties.resendMax()).orElseThrow(() -> new IllegalStateException(
                    "Locked order left CONFIRM_SENT/RELEASE_SENT: orderId=" + o.id().getValue()));
            resent.add(new Resent(new OrderRef(o.id(), o.merchantId()), next.stockState(), next.stockAttempts()));
        }
        outbox.saveAll(messages);
        return resent;
    }

    /** One re-sent command: its order, which command (from the stock state) and the attempt count now stored. */
    public record Resent(OrderRef order, StockState stockState, int attempts) {
    }

    /**
     * Decides a PENDING checkout whose orders are locked by the caller: wait, place all (numbers, Confirm per order,
     * CheckoutPlaced) or reject all (Release for every held or unanswered order, slots back, CheckoutRejected).
     */
    void decide(Checkout checkout, boolean timedOut, Instant now) {
        if (checkout.status() != CheckoutStatus.PENDING) {
            return;
        }
        List<Order> current = orders.findByCheckout(checkout.id(), checkout.customerId());
        switch (CheckoutDecision.decide(current, timedOut)) {
            case WAIT -> {
            }
            case FINALIZE -> finalizePlacement(checkout, current, now);
            case REJECT -> reject(checkout, current, timedOut, now);
        }
    }

    private void finalizePlacement(Checkout checkout, List<Order> current, Instant now) {
        List<OutboxMessage> messages = new ArrayList<>();
        for (Order o : current.stream().sorted(Order.BY_STORE_ID).toList()) {
            int seq = counters.nextSeq(o.storeId(), o.localDate(), o.merchantId()).orElseThrow(() ->
                    new IllegalStateException("No day counter for storeId=" + o.storeId().getValue()));
            String number = OrderNumber.of(o.storeCode(), o.localDate(), seq);
            if (!orders.transition(base(o, OrderStatus.PENDING, StockState.RESERVED, OrderStatus.PLACED,
                    StockState.CONFIRM_SENT, now).stockDueAt(now.plus(properties.resendInitial())).orderSeq(seq)
                    .orderNumber(number).placedAt(now).reason("STOCK_RESERVED").build())) {
                throw new IllegalStateException("Finalize found orderId=" + o.id().getValue() + " moved");
            }
            messages.add(commands.confirm(o, now));
        }
        if (!checkouts.markPlaced(checkout.id(), checkout.customerId(), now)) {
            throw new IllegalStateException("Finalize found checkoutId=" + checkout.id().getValue() + " decided");
        }
        List<Order> placed = orders.findByCheckout(checkout.id(), checkout.customerId());
        messages.add(replies.placed(checkout, placed, checkout.causationId(), now));
        outbox.saveAll(messages);
        metrics.placed();
        log.info("Checkout placed: checkoutId={} orders={}", checkout.id().getValue(),
                placed.stream().map(Order::orderNumber).toList());
    }

    private void reject(Checkout checkout, List<Order> current, boolean timedOut, Instant now) {
        List<RejectionReason> reasons = CheckoutDecision.checkoutReasons(current);
        String releaseReason = timedOut ? RESERVE_TIMEOUT : CHECKOUT_REJECTED;
        List<OutboxMessage> messages = new ArrayList<>();
        for (Order o : current) {
            StockState from = o.stockState();
            boolean hold = from == StockState.RESERVED || from == StockState.RESERVE_SENT;
            OrderTransition.OrderTransitionBuilder t = base(o, OrderStatus.PENDING, from, OrderStatus.REJECTED,
                    hold ? StockState.RELEASE_SENT : from, now).reason(releaseReason);
            if (hold) {
                // Held stock, or a reserve still unanswered at the deadline: release it (inventory accepts a release
                // before the reserve). Re-sent until StockReservationReleased (ADR 0006 obligation 2).
                t.stockDueAt(now.plus(properties.resendInitial())).releaseRestock(true).releaseReason(releaseReason);
                messages.add(commands.release(o, true, releaseReason, now));
            }
            if (!orders.transition(t.build())) {
                throw new IllegalStateException("Reject found orderId=" + o.id().getValue() + " moved");
            }
        }
        for (Order o : current.stream().sorted(Order.BY_STORE_ID).toList()) {
            giveBack(o);
        }
        if (!checkouts.markRejected(checkout.id(), checkout.customerId(), reasons, now)) {
            throw new IllegalStateException("Reject found checkoutId=" + checkout.id().getValue() + " decided");
        }
        messages.add(replies.rejected(checkout, reasons, now));
        outbox.saveAll(messages);
        reasons.stream().map(RejectionReason::code).distinct().forEach(metrics::rejected);
        if (timedOut) {
            metrics.reserveTimeout();
        }
        log.info("Checkout rejected after placement: checkoutId={} timedOut={} codes={}", checkout.id().getValue(),
                timedOut, reasons.stream().map(r -> r.code().name()).distinct().toList());
    }

    /** BR-ORD-16: stock not confirmed → the order is cancelled, its slot given back and its lines released. */
    private boolean confirmationFailed(Order order, StockReplyCmd reply, Instant now) {
        boolean moved = move(order, OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.CANCELLED,
                StockState.RELEASE_SENT, now, b -> b.stockDueAt(now.plus(properties.resendInitial()))
                        .releaseRestock(true).releaseReason(STOCK_NOT_CONFIRMED).reason(STOCK_NOT_CONFIRMED));
        if (moved) {
            // Confirm is all or nothing: no line is CONFIRMED, so restock = true changes nothing on inventory's side.
            giveBack(order);
            outbox.saveAll(List.of(commands.release(order, true, STOCK_NOT_CONFIRMED, now)));
            log.warn("Stock not confirmed ({}): orderId={} cancelled and released",
                    LogSanitizer.escape(String.valueOf(reply.failureReason())),
                    order.id().getValue());
        }
        return moved;
    }

    private void giveBack(Order o) {
        if (!counters.giveBack(o.storeId(), o.localDate(), o.merchantId())) {
            log.error("Daily slot not given back (count 0 or another merchant's row): merchantId={} storeId={} "
                    + "orderId={}", o.merchantId().getValue(), o.storeId().getValue(), o.id().getValue());
            metrics.counterGiveBackMissed();
        }
    }

    private boolean move(Order o, OrderStatus fromStatus, StockState fromStock, OrderStatus toStatus,
                         StockState toStock, Instant now,
                         Function<OrderTransition.OrderTransitionBuilder, OrderTransition.OrderTransitionBuilder> extra) {
        if (o.status() != fromStatus || o.stockState() != fromStock) {
            return false;
        }
        return orders.transition(extra.apply(base(o, fromStatus, fromStock, toStatus, toStock, now)).build());
    }

    private static OrderTransition.OrderTransitionBuilder base(Order o, OrderStatus fromStatus, StockState fromStock,
                                                               OrderStatus toStatus, StockState toStock, Instant now) {
        return OrderTransition.builder().orderId(o.id()).merchantId(o.merchantId()).checkoutId(o.checkoutId())
                .fromStatus(fromStatus).fromStock(fromStock).toStatus(toStatus).toStock(toStock).now(now);
    }

    /**
     * The reply's lines must belong to the order: StockReserved lists exactly its lines (id, SKU, quantity); a
     * rejection a subset by id with the same SKU; the other replies a subset by id.
     */
    static void checkLines(StockReplyCmd reply, Order order) {
        Map<Object, OrderLine> byId = order.lines().stream()
                .collect(Collectors.toMap(l -> l.id().getValue(), Function.identity()));
        Set<Object> seen = new java.util.HashSet<>();
        for (StockReplyCmd.Line line : reply.lines()) {
            OrderLine own = byId.get(line.orderItemId().getValue());
            if (own == null || !seen.add(line.orderItemId().getValue())) {
                throw new InvalidStockReplyException("lines: orderItemId not of this order or repeated");
            }
            boolean needsSku = reply.type() == StockReplyCmd.Type.RESERVED
                    || reply.type() == StockReplyCmd.Type.RESERVATION_REJECTED;
            if (needsSku && !own.sku().equals(line.sku())) {
                throw new InvalidStockReplyException("lines: sku differs from the order line");
            }
            if (reply.type() == StockReplyCmd.Type.RESERVED
                    && (line.quantity() == null || line.quantity() != own.quantity())) {
                throw new InvalidStockReplyException("lines: quantity differs from the order line");
            }
        }
        if (reply.type() == StockReplyCmd.Type.RESERVED && seen.size() != byId.size()) {
            throw new InvalidStockReplyException("lines: StockReserved does not list every order line");
        }
    }
}
