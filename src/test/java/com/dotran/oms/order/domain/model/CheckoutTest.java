package com.dotran.oms.order.domain.model;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutTest {

    @Test
    @DisplayName("pending carries reserveDueAt; a PENDING checkout without it is refused")
    void pending() {
        Instant now = Instant.parse("2026-11-21T03:00:00Z");
        Checkout c = Checkout.pending(CustomerId.of(UUID.randomUUID()), CartId.of(UUID.randomUUID()), 3,
                "a".repeat(64), EventId.of(UUID.randomUUID()), now, now, now.plusSeconds(150));
        assertThat(c.status()).isEqualTo(CheckoutStatus.PENDING);
        assertThat(c.reserveDueAt()).isEqualTo(now.plusSeconds(150));
        assertThat(c.answersWithRejection()).isFalse();
        assertThat(c.isPlaced()).isFalse();
        assertThatThrownBy(() -> new Checkout(CheckoutId.newId(), c.customerId(), c.cartId(), 3,
                CheckoutStatus.PENDING, c.contentHash(), null, c.causationId(), now, now, null, null, now))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
