package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.support.Orders;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.dotran.oms.order.support.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutSqlIT extends PostgresIT {

    @Autowired
    private CheckoutRepository checkouts;

    private static Checkout rejected(UUID customer, UUID cart, String hash) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID store = UUID.randomUUID();
        return Checkout.rejected(CheckoutStatus.REJECTED, CustomerId.of(customer), CartId.of(cart), 3, hash,
                List.of(RejectionReason.store(RejectionCode.STORE_CLOSED, store),
                        RejectionReason.line(RejectionCode.UNKNOWN_SKU, store, "TS-M"),
                        RejectionReason.checkout(RejectionCode.INVALID_REQUEST)),
                EventId.of(UUID.randomUUID()), now.minusSeconds(5), now);
    }

    @Test
    @DisplayName("insert, then read back by key and customer: every field round-trips (reasons JSONB, instants)")
    void roundTrip() {
        UUID customer = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        Checkout row = rejected(customer, cart, "a".repeat(64));
        assertThat(checkouts.insertIfAbsent(row)).isTrue();
        assertThat(checkouts.findByKey(CartId.of(cart), 3, CustomerId.of(customer))).contains(row);
    }

    @Test
    @DisplayName("a second insert under the same key writes nothing and returns false (other id, other content)")
    void conflict() {
        UUID customer = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        Checkout first = rejected(customer, cart, "a".repeat(64));
        assertThat(checkouts.insertIfAbsent(first)).isTrue();
        assertThat(checkouts.insertIfAbsent(rejected(customer, cart, "b".repeat(64)))).isFalse();
        assertThat(count("SELECT count(*) FROM checkouts WHERE cart_id = ?", cart)).isEqualTo(1);
        assertThat(checkouts.findByKey(CartId.of(cart), 3, CustomerId.of(customer)).orElseThrow().contentHash())
                .isEqualTo("a".repeat(64));
    }

    @Test
    @DisplayName("customer scope: another customer's key is never read, and their insert under it writes nothing")
    void customerScope() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID cart = UUID.randomUUID();
        assertThat(checkouts.insertIfAbsent(rejected(owner, cart, "a".repeat(64)))).isTrue();
        assertThat(checkouts.findByKey(CartId.of(cart), 3, CustomerId.of(other))).isEmpty();
        assertThat(checkouts.insertIfAbsent(rejected(other, cart, "a".repeat(64)))).isFalse();
        assertThat(count("SELECT count(*) FROM checkouts WHERE customer_id = ?", other)).isZero();
    }

    @Test
    @DisplayName("a PENDING checkout round-trips with reserve_due_at; lockForUpdate is customer-scoped")
    void pendingRoundTrip() {
        UUID customer = UUID.randomUUID();
        Checkout row = Orders.pendingCheckout(customer, Orders.now());
        assertThat(checkouts.insertIfAbsent(row)).isTrue();
        assertThat(inTx(() -> checkouts.lockForUpdate(row.id(), CustomerId.of(customer)))).contains(row);
        assertThat(inTx(() -> checkouts.lockForUpdate(row.id(), CustomerId.of(UUID.randomUUID())))).isEmpty();
    }

    @Test
    @DisplayName("markPlaced / markRejected: only PENDING, only the owner; a second call writes nothing")
    void guardedMarks() {
        UUID customer = UUID.randomUUID();
        Instant now = Orders.now();
        Checkout a = Orders.pendingCheckout(customer, now);
        Checkout b = Orders.pendingCheckout(customer, now);
        checkouts.insertIfAbsent(a);
        checkouts.insertIfAbsent(b);
        CustomerId owner = CustomerId.of(customer);
        CustomerId other = CustomerId.of(UUID.randomUUID());
        List<RejectionReason> reasons = List.of(RejectionReason.store(RejectionCode.RESERVE_TIMEOUT, UUID.randomUUID()));

        assertThat(checkouts.markPlaced(a.id(), other, now)).isFalse();
        assertThat(checkouts.markPlaced(a.id(), owner, now)).isTrue();
        assertThat(checkouts.markPlaced(a.id(), owner, now)).isFalse();
        assertThat(checkouts.markRejected(a.id(), owner, reasons, now)).isFalse();

        assertThat(checkouts.markRejected(b.id(), other, reasons, now)).isFalse();
        assertThat(checkouts.markRejected(b.id(), owner, reasons, now)).isTrue();
        Checkout rejected = checkouts.findByKey(b.cartId(), 3, owner).orElseThrow();
        assertThat(rejected.status()).isEqualTo(CheckoutStatus.REJECTED);
        assertThat(rejected.reasons()).isEqualTo(reasons);
        assertThat(checkouts.findByKey(a.cartId(), 3, owner).orElseThrow().placedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("lockNextDueReserve: oldest due PENDING first, excluded ids skipped (empty and 2-id sets), not-due ignored")
    void nextDue() {
        UUID customer = UUID.randomUUID();
        Instant now = Orders.now();
        Checkout oldest = Orders.pendingCheckout(customer, now.minusSeconds(300));
        Checkout older = Orders.pendingCheckout(customer, now.minusSeconds(200));
        Checkout old = Orders.pendingCheckout(customer, now.minusSeconds(160));
        Checkout fresh = Orders.pendingCheckout(customer, now);
        List.of(oldest, older, old, fresh).forEach(checkouts::insertIfAbsent);

        assertThat(inTx(() -> checkouts.lockNextDueReserve(now, Set.of())).map(Checkout::id))
                .contains(oldest.id());
        assertThat(inTx(() -> checkouts.lockNextDueReserve(now,
                Set.of(oldest.id().getValue(), older.id().getValue()))).map(Checkout::id)).contains(old.id());
        assertThat(inTx(() -> checkouts.lockNextDueReserve(now, Set.of(oldest.id().getValue(),
                older.id().getValue(), old.id().getValue())))).isEmpty();
    }

    @Test
    @DisplayName("deferReserve moves only a PENDING row of the owner; created_at (the gauge) never moves")
    void defer() {
        UUID customer = UUID.randomUUID();
        Instant now = Orders.now();
        Checkout row = Orders.pendingCheckout(customer, now.minusSeconds(400));
        checkouts.insertIfAbsent(row);
        assertThat(checkouts.deferReserve(row.id(), CustomerId.of(UUID.randomUUID()), now.plusSeconds(60), now)).isFalse();
        assertThat(checkouts.deferReserve(row.id(), CustomerId.of(customer), now.plusSeconds(60), now)).isTrue();
        assertThat(inTx(() -> checkouts.lockNextDueReserve(now, Set.of()))).isEmpty();
        assertThat(checkouts.oldestPendingCreatedAt()).contains(now.minusSeconds(400));

        checkouts.markPlaced(row.id(), CustomerId.of(customer), now);
        assertThat(checkouts.deferReserve(row.id(), CustomerId.of(customer), now.plusSeconds(120), now)).isFalse();
        assertThat(checkouts.oldestPendingCreatedAt()).isEmpty();
    }
}
