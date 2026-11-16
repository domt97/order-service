package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.repository.OutboxEventRepository;
import com.dotran.oms.order.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.order.domain.model.Checkout;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * The write transactions of a checkout, in their own bean so the proxy applies {@code @Transactional} (a self-call from
 * {@link PlaceCheckoutService} would not). Bounded as ADR 0007 *Timeouts* requires: lock 2 s, statement 3 s,
 * transaction 5 s.
 */
@Service
@RequiredArgsConstructor
public class CheckoutTransactions {

    static final Duration LOCK_TIMEOUT = Duration.ofSeconds(2);
    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(3);
    static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final CheckoutRepository checkoutRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTimeoutRepository transactionTimeoutRepository;

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
}
