package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.event.StockCommandFactory;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.repository.OrderRepository;
import com.dotran.oms.order.application.repository.StoreDayCounterRepository;
import com.dotran.oms.order.application.repository.OutboxEventRepository;
import com.dotran.oms.order.application.repository.TransactionTimeoutRepository;
import com.dotran.oms.order.domain.model.Checkout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CheckoutTransactions")
class CheckoutTransactionsTest {

    @Mock private CheckoutRepository checkoutRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private StoreDayCounterRepository counters;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private TransactionTimeoutRepository timeouts;

    @Test
    @DisplayName("storeOutcome: timeouts first, then the insert, then the reply; no reply when the insert lost")
    void storeOutcome() throws Exception {
        CheckoutTransactions tx = new CheckoutTransactions(checkoutRepository, orderRepository, counters, outboxEventRepository, timeouts,
                new StockCommandFactory(), new CheckoutReplyFactory());
        Checkout row = mock(Checkout.class);
        OutboxMessage reply = mock(OutboxMessage.class);
        when(checkoutRepository.insertIfAbsent(row)).thenReturn(true).thenReturn(false);

        assertThat(tx.storeOutcome(row, reply)).isTrue();
        InOrder order = inOrder(timeouts, checkoutRepository, outboxEventRepository);
        order.verify(timeouts).applyLocal(Duration.ofSeconds(2), Duration.ofSeconds(3));
        order.verify(checkoutRepository).insertIfAbsent(row);
        order.verify(outboxEventRepository).saveAll(List.of(reply));

        assertThat(tx.storeOutcome(row, reply)).isFalse();
        verify(outboxEventRepository, org.mockito.Mockito.times(1)).saveAll(any());

        Transactional annotation = CheckoutTransactions.class.getMethod("storeOutcome", Checkout.class,
                OutboxMessage.class).getAnnotation(Transactional.class);
        assertThat(annotation.timeout()).isEqualTo(5);
        assertThat(CheckoutTransactions.class.getMethod("publish", OutboxMessage.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(5);
        // The PlaceCheckout poll sizing (200 s, application.yaml) counts on these two.
        assertThat(CheckoutTransactions.class.getMethod("place",
                com.dotran.oms.order.domain.valueobject.PlacementPlan.class, java.time.Instant.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(10);
        assertThat(CheckoutTransactions.class.getMethod("republishPlaced", Checkout.class,
                com.dotran.oms.core.domain.id.EventId.class, java.time.Instant.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(5);
    }

    @Test
    @DisplayName("publish: timeouts, then the reply; no checkout write")
    void publish() {
        CheckoutTransactions tx = new CheckoutTransactions(checkoutRepository, orderRepository, counters, outboxEventRepository, timeouts,
                new StockCommandFactory(), new CheckoutReplyFactory());
        OutboxMessage reply = mock(OutboxMessage.class);
        tx.publish(reply);
        verify(outboxEventRepository).saveAll(List.of(reply));
        verify(checkoutRepository, never()).insertIfAbsent(any());
    }
}
