package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.dotran.oms.order.support.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutTransactionIT extends PostgresIT {

    @Autowired
    private CheckoutTransactions transactions;

    @Autowired
    private TransactionTimeoutRepository timeouts;

    @Test
    @DisplayName("the reply cannot be written after the checkout insert: the whole transaction rolls back, no row left")
    void rollbackWhenOutboxFails() {
        UUID cart = UUID.randomUUID();
        Checkout row = Checkout.rejected(CheckoutStatus.REJECTED, CustomerId.of(UUID.randomUUID()), CartId.of(cart), 1,
                "a".repeat(64), List.of(RejectionReason.checkout(RejectionCode.INVALID_REQUEST)),
                EventId.of(UUID.randomUUID()), Instant.now(), Instant.now());
        // A payload Jackson cannot serialise: the outbox adapter throws after the checkout INSERT ran.
        OutboxMessage unwritable = new OutboxMessage(UUID.randomUUID(), UUID.randomUUID(), null, "Checkout", cart,
                "CheckoutRejected", new Object());
        assertThatThrownBy(() -> transactions.storeOutcome(row, unwritable)).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ?", cart)).isZero();
    }

    @Test
    @DisplayName("lock_timeout and statement_timeout are set for the transaction only")
    void timeoutsLocal() {
        List<String> inside = tx.execute(status -> {
            timeouts.applyLocal(Duration.ofSeconds(2), Duration.ofSeconds(3));
            return List.of(jdbc.queryForObject("SHOW lock_timeout", String.class),
                    jdbc.queryForObject("SHOW statement_timeout", String.class));
        });
        assertThat(inside).containsExactly("2s", "3s");
        // A new transaction (maybe on the same pooled connection) has the server defaults again.
        List<String> after = tx.execute(status -> List.of(jdbc.queryForObject("SHOW lock_timeout", String.class),
                jdbc.queryForObject("SHOW statement_timeout", String.class)));
        assertThat(after).containsExactly("0", "0");
    }
}
