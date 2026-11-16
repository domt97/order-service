package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.client.OrderQuoteClient;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.config.CheckoutProperties;
import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.application.event.CheckoutRejectedEvent;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.application.usecase.checkout.PlaceCheckoutUseCase.PlaceCheckoutOutcome;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.dotran.oms.order.support.Checkouts;
import com.dotran.oms.order.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dotran.oms.order.support.Checkouts.NOW;
import static com.dotran.oms.order.support.Checkouts.PRODUCT;
import static com.dotran.oms.order.support.Checkouts.STORE;
import static com.dotran.oms.order.support.Checkouts.line;
import static com.dotran.oms.order.support.Checkouts.store;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlaceCheckoutService")
class PlaceCheckoutServiceTest {

    @Mock private CheckoutRepository checkoutRepository;
    @Mock private CheckoutTransactions transactions;
    @Mock private OrderQuoteClient orderQuoteClient;

    private final MutableClock clock = new MutableClock(NOW);
    private PlaceCheckoutService service;

    @BeforeEach
    void setUp() {
        CheckoutProperties properties = new CheckoutProperties(Duration.ofMinutes(10), Duration.ofSeconds(30),
                Duration.ofSeconds(20));
        service = new PlaceCheckoutService(checkoutRepository, transactions, new PlaceCheckoutValidator(properties),
                new QuoteReasonMapper(), orderQuoteClient, new CheckoutReplyFactory(), properties, clock);
    }

    private static QuoteResult closedStore() {
        return new QuoteResult.Ok(new OrderQuote("ACTIVE", "VND", false, List.of("OUTSIDE_HOURS"),
                List.of(new OrderQuote.SkuQuote("TS-M", true, PRODUCT, "ACTIVE", "ACTIVE", "VND"))));
    }

    private static QuoteResult openStore() {
        return new QuoteResult.Ok(new OrderQuote("ACTIVE", "VND", true, List.of(),
                List.of(new OrderQuote.SkuQuote("TS-M", true, PRODUCT, "ACTIVE", "ACTIVE", "VND"))));
    }

    private static Checkout stored(PlaceCheckoutCmd cmd, CheckoutStatus status, RejectionCode code) {
        return new Checkout(CheckoutId.newId(), cmd.customerId(), cmd.cartId(), cmd.cartVersion(), status,
                ContentHash.of(cmd), List.of(RejectionReason.store(code, STORE)), EventId.of(UUID.randomUUID()),
                cmd.requestedAt(), NOW.minusSeconds(60));
    }

    private static CheckoutRejectedEvent payload(OutboxMessage message) {
        return (CheckoutRejectedEvent) message.payload();
    }

    @Test
    @DisplayName("a closed store: REJECTED row and one CheckoutRejected with the checkoutId, in one call")
    void rejectedStored() {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        when(checkoutRepository.findByKey(cmd.cartId(), 3, cmd.customerId())).thenReturn(Optional.empty());
        when(orderQuoteClient.quote(eq(Checkouts.MERCHANT), eq(STORE), eq(List.of("TS-M")), any())).thenReturn(closedStore());
        when(transactions.storeOutcome(any(), any())).thenReturn(true);

        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.REJECTED_STORED);

        ArgumentCaptor<Checkout> row = ArgumentCaptor.forClass(Checkout.class);
        ArgumentCaptor<OutboxMessage> reply = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(transactions).storeOutcome(row.capture(), reply.capture());
        assertThat(row.getValue().status()).isEqualTo(CheckoutStatus.REJECTED);
        assertThat(row.getValue().reasons()).containsExactly(RejectionReason.store(RejectionCode.STORE_CLOSED, STORE));
        assertThat(row.getValue().causationId()).isEqualTo(cmd.eventId());
        CheckoutRejectedEvent event = payload(reply.getValue());
        assertThat(event.checkoutId()).isEqualTo(row.getValue().id().getValue());
        assertThat(event.causationId()).isEqualTo(cmd.eventId().getValue());
        assertThat(reply.getValue().aggregateId()).isEqualTo(Checkouts.CART);
        assertThat(reply.getValue().customerId()).isEqualTo(Checkouts.CUSTOMER);
        assertThat(reply.getValue().merchantId()).isNull();
        verify(transactions, never()).publish(any());
    }

    @Test
    @DisplayName("every check passes: nothing stored, nothing sent (placement not built yet)")
    void passedNotPlaced() {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        when(checkoutRepository.findByKey(any(), eq(3L), any())).thenReturn(Optional.empty());
        when(orderQuoteClient.quote(any(), any(), anyList(), any())).thenReturn(openStore());

        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.PASSED_NOT_PLACED);
        verify(transactions, never()).storeOutcome(any(), any());
        verify(transactions, never()).publish(any());
    }

    @Test
    @DisplayName("stored row, same content: its reasons sent again with its checkoutId; no quote, no insert")
    void republish() {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        Checkout row = stored(cmd, CheckoutStatus.REJECTED, RejectionCode.STORE_CLOSED);
        when(checkoutRepository.findByKey(cmd.cartId(), 3, cmd.customerId())).thenReturn(Optional.of(row));

        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.REPUBLISHED);

        ArgumentCaptor<OutboxMessage> reply = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(transactions).publish(reply.capture());
        assertThat(payload(reply.getValue()).checkoutId()).isEqualTo(row.id().getValue());
        assertThat(payload(reply.getValue()).reasons()).extracting(CheckoutRejectedEvent.Reason::code)
                .containsExactly("STORE_CLOSED");
        assertThat(payload(reply.getValue()).causationId()).isEqualTo(cmd.eventId().getValue());
        verify(orderQuoteClient, never()).quote(any(), any(), anyList(), any());
        verify(transactions, never()).storeOutcome(any(), any());
    }

    @Test
    @DisplayName("stored row, other content: CONTENT_MISMATCH without checkoutId; no row written or changed")
    void contentMismatch() {
        PlaceCheckoutCmd first = Checkouts.valid().build();
        Checkout row = stored(first, CheckoutStatus.REJECTED, RejectionCode.STORE_CLOSED);
        PlaceCheckoutCmd other = Checkouts.valid().stores(List.of(store(STORE, line("OTHER")))).build();
        when(checkoutRepository.findByKey(other.cartId(), 3, other.customerId())).thenReturn(Optional.of(row));

        assertThat(service.place(other)).isEqualTo(PlaceCheckoutOutcome.CONTENT_MISMATCH);

        ArgumentCaptor<OutboxMessage> reply = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(transactions).publish(reply.capture());
        assertThat(payload(reply.getValue()).checkoutId()).isNull();
        assertThat(payload(reply.getValue()).reasons()).extracting(CheckoutRejectedEvent.Reason::code)
                .containsExactly("CONTENT_MISMATCH");
        verify(transactions, never()).storeOutcome(any(), any());
        verify(orderQuoteClient, never()).quote(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("STALE: exactly 10 min old is not stale; 10 min + 1 s is: STALE row, no quote")
    void stale() {
        when(checkoutRepository.findByKey(any(), eq(3L), any())).thenReturn(Optional.empty());
        when(orderQuoteClient.quote(any(), any(), anyList(), any())).thenReturn(openStore());
        assertThat(service.place(Checkouts.valid().requestedAt(NOW.minus(Duration.ofMinutes(10))).build()))
                .isEqualTo(PlaceCheckoutOutcome.PASSED_NOT_PLACED);

        when(transactions.storeOutcome(any(), any())).thenReturn(true);
        assertThat(service.place(Checkouts.valid().requestedAt(NOW.minus(Duration.ofMinutes(10)).minusSeconds(1)).build()))
                .isEqualTo(PlaceCheckoutOutcome.REJECTED_STORED);
        ArgumentCaptor<Checkout> row = ArgumentCaptor.forClass(Checkout.class);
        verify(transactions).storeOutcome(row.capture(), any());
        assertThat(row.getValue().status()).isEqualTo(CheckoutStatus.STALE);
        assertThat(row.getValue().reasons()).containsExactly(RejectionReason.checkout(RejectionCode.STALE));
        verify(orderQuoteClient, times(1)).quote(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("invalid and stale: INVALID_REQUEST wins (validated before the STALE check), no quote")
    void invalidBeforeStale() {
        when(checkoutRepository.findByKey(any(), eq(3L), any())).thenReturn(Optional.empty());
        when(transactions.storeOutcome(any(), any())).thenReturn(true);
        service.place(Checkouts.valid().requestedAt(NOW.minus(Duration.ofHours(1))).delivery(null).build());
        ArgumentCaptor<Checkout> row = ArgumentCaptor.forClass(Checkout.class);
        verify(transactions).storeOutcome(row.capture(), any());
        assertThat(row.getValue().status()).isEqualTo(CheckoutStatus.REJECTED);
        assertThat(row.getValue().reasons()).containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        verify(orderQuoteClient, never()).quote(any(), any(), anyList(), any());
    }

    @Test
    @DisplayName("lost insert, re-read same content: the winner's outcome is sent; never a second row")
    void lostInsertSameContent() {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        Checkout winner = stored(cmd, CheckoutStatus.REJECTED, RejectionCode.STORE_CLOSED);
        when(checkoutRepository.findByKey(cmd.cartId(), 3, cmd.customerId()))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(orderQuoteClient.quote(any(), any(), anyList(), any())).thenReturn(closedStore());
        when(transactions.storeOutcome(any(), any())).thenReturn(false);

        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.REPUBLISHED);
        ArgumentCaptor<OutboxMessage> reply = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(transactions).publish(reply.capture());
        assertThat(payload(reply.getValue()).checkoutId()).isEqualTo(winner.id().getValue());
        verify(transactions, times(1)).storeOutcome(any(), any());
    }

    @Test
    @DisplayName("lost insert, re-read other content → CONTENT_MISMATCH; empty re-read (another customer's key) too")
    void lostInsertMismatch() {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        PlaceCheckoutCmd otherContent = Checkouts.valid().stores(List.of(store(STORE, line("OTHER")))).build();
        Checkout winner = stored(otherContent, CheckoutStatus.REJECTED, RejectionCode.STORE_CLOSED);
        when(checkoutRepository.findByKey(cmd.cartId(), 3, cmd.customerId()))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(winner))
                .thenReturn(Optional.empty()).thenReturn(Optional.empty());
        when(orderQuoteClient.quote(any(), any(), anyList(), any())).thenReturn(closedStore());
        when(transactions.storeOutcome(any(), any())).thenReturn(false);

        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.CONTENT_MISMATCH);
        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.CONTENT_MISMATCH);
        ArgumentCaptor<OutboxMessage> reply = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(transactions, times(2)).publish(reply.capture());
        assertThat(reply.getAllValues()).allSatisfy(m -> assertThat(payload(m).checkoutId()).isNull());
    }

    @Test
    @DisplayName("STALE lost insert: same content → republish, other content → CONTENT_MISMATCH")
    void staleLostInsert() {
        PlaceCheckoutCmd cmd = Checkouts.valid().requestedAt(NOW.minus(Duration.ofMinutes(11))).build();
        Checkout winner = stored(cmd, CheckoutStatus.STALE, RejectionCode.STALE);
        when(checkoutRepository.findByKey(cmd.cartId(), 3, cmd.customerId()))
                .thenReturn(Optional.empty()).thenReturn(Optional.of(winner));
        when(transactions.storeOutcome(any(), any())).thenReturn(false);
        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.REPUBLISHED);
    }

    @Test
    @DisplayName("budget used up after the first store: the others are STORE_UNAVAILABLE without a call")
    void budget() {
        UUID secondStore = UUID.fromString("00000000-0000-0000-0000-00000000b002");
        PlaceCheckoutCmd cmd = Checkouts.valid().stores(List.of(store(STORE, line("TS-M")), store(secondStore,
                line("TS-M")))).build();
        when(checkoutRepository.findByKey(any(), eq(3L), any())).thenReturn(Optional.empty());
        when(orderQuoteClient.quote(any(), eq(STORE), anyList(), any())).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(20));
            return openStore();
        });
        when(transactions.storeOutcome(any(), any())).thenReturn(true);

        service.place(cmd);
        ArgumentCaptor<Checkout> row = ArgumentCaptor.forClass(Checkout.class);
        verify(transactions).storeOutcome(row.capture(), any());
        assertThat(row.getValue().reasons())
                .containsExactly(RejectionReason.store(RejectionCode.STORE_UNAVAILABLE, secondStore));
        verify(orderQuoteClient, never()).quote(any(), eq(secondStore), anyList(), any());
    }

    @Test
    @DisplayName("each call gets what is left of the budget")
    void timeLeftPassed() {
        when(checkoutRepository.findByKey(any(), eq(3L), any())).thenReturn(Optional.empty());
        when(orderQuoteClient.quote(any(), any(), anyList(), any())).thenReturn(openStore());
        service.place(Checkouts.valid().build());
        verify(orderQuoteClient).quote(any(), any(), anyList(), eq(Duration.ofSeconds(20)));
    }

    @Test
    @DisplayName("a PENDING row (cannot exist yet): nothing sent")
    void inProgress() {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        Checkout pending = new Checkout(CheckoutId.newId(), cmd.customerId(), cmd.cartId(), 3, CheckoutStatus.PENDING,
                ContentHash.of(cmd), null, EventId.of(UUID.randomUUID()), cmd.requestedAt(), NOW);
        when(checkoutRepository.findByKey(any(), eq(3L), any())).thenReturn(Optional.of(pending));
        assertThat(service.place(cmd)).isEqualTo(PlaceCheckoutOutcome.IN_PROGRESS);
        verify(transactions, never()).publish(any());
        verify(transactions, never()).storeOutcome(any(), any());
    }
}
