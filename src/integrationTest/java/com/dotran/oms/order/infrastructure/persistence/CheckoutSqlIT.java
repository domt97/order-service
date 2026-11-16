package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.repository.CheckoutRepository;
import com.dotran.oms.order.common.id.CartId;
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
}
