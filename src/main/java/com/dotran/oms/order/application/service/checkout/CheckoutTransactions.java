package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.event.StockCommandFactory;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.repository.OrderRepository;
import com.dotran.oms.order.application.repository.OutboxEventRepository;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository.SlotResult;
import com.dotran.oms.order.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.valueobject.PlacementPlan;
import com.dotran.oms.order.domain.valueobject.StoreOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The write transactions of a checkout, in their own bean so the proxy applies {@code @Transactional} (a self-call from
 * {@link PlaceCheckoutService} would not). Bounded as ADR 0007 *Timeouts* requires: lock 2 s, statement 3 s,
 * transaction 5 s (placement: 10 s, it writes up to 10 orders × 100 lines).
 */
@Service
@RequiredArgsConstructor
public class CheckoutTransactions {

    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(3);
    static final int TRANSACTION_TIMEOUT_SECONDS = 5;
    static final int PLACEMENT_TIMEOUT_SECONDS = 10;

    private final CheckoutRepository checkoutRepository;
    private final OrderRepository orderRepository;
    private final StoreDayCounterRepository counters;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTimeoutRepository transactionTimeoutRepository;
    private final StockCommandFactory stockCommands;
    private final CheckoutReplyFactory replies;

    /**
     * Starts the placement (ADR 0007): the PENDING checkout under its key, the orders with their lines and history,
     * one ReserveStock per order, and last one daily slot per store in store id order. KEY_TAKEN (nothing written)
     * when the key already has a row. Throws {@link DailyCapReached} naming every capped store: the whole transaction
     * rolls back.
     */
    @Transactional(timeout = PLACEMENT_TIMEOUT_SECONDS)
    public PlaceResult place(PlacementPlan plan, Instant now) {
        transactionTimeoutRepository.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        if (!checkoutRepository.insertIfAbsent(plan.checkout())) {
            return PlaceResult.KEY_TAKEN;
        }
        List<Order> orders = plan.orders();
        orderRepository.insertAll(orders);
        outboxEventRepository.saveAll(orders.stream().map(o -> stockCommands.reserve(o, now)).toList());
        List<UUID> capped = new ArrayList<>();
        for (StoreOrder store : plan.stores()) { // BY_STORE_ID order (PlacementPlan): no lock cycle
            Order o = store.order();
            if (counters.takeSlot(o.storeId(), o.localDate(), o.merchantId(), store.maxOrdersPerDay())
                    == SlotResult.CAPPED) {
                capped.add(o.storeId().getValue());
            }
        }
        if (!capped.isEmpty()) {
            throw new DailyCapReached(capped);
        }
        return PlaceResult.PLACEMENT_STARTED;
    }

    /** CheckoutPlaced again for a copy of the PlaceCheckout of a PLACED checkout (BR-ORD-05). */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void republishPlaced(Checkout checkout, EventId causationId, Instant now) {
        transactionTimeoutRepository.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        List<Order> orders = orderRepository.findByCheckout(checkout.id(), checkout.customerId());
        outboxEventRepository.saveAll(List.of(replies.placed(checkout, orders, causationId, now)));
    }

    /** Stores the outcome and its reply together; false (nothing written) when the key already has a row. */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public boolean storeOutcome(Checkout checkout, OutboxMessage reply) {
        transactionTimeoutRepository.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        boolean inserted = checkoutRepository.insertIfAbsent(checkout);
        if (inserted) {
            outboxEventRepository.saveAll(List.of(reply));
        }
        return inserted;
    }

    /** A reply without a checkout change (republished outcome, CONTENT_MISMATCH). */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public void publish(OutboxMessage reply) {
        transactionTimeoutRepository.applyLocal(LOCK_TIMEOUT, STATEMENT_TIMEOUT);
        outboxEventRepository.saveAll(List.of(reply));
    }

    public enum PlaceResult {
        /** Checkout PENDING, orders and ReserveStock written. */
        PLACEMENT_STARTED,
        /** Another row holds the key: nothing written. */
        KEY_TAKEN
    }
}
