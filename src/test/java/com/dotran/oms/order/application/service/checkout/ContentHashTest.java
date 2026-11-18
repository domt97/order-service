package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.support.Checkouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dotran.oms.order.support.Checkouts.STORE;
import static com.dotran.oms.order.support.Checkouts.line;
import static com.dotran.oms.order.support.Checkouts.store;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ContentHash")
class ContentHashTest {

    private static final UUID OTHER_STORE = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    @Test
    @DisplayName("eventId, occurredAt and requestedAt do not change it; store and line order do not either")
    void ignoresCopyFieldsAndOrder() {
        PlaceCheckoutCmd.Line a = line("A");
        PlaceCheckoutCmd.Line b = line("B");
        PlaceCheckoutCmd first = Checkouts.valid().stores(List.of(store(STORE, a, b), store(OTHER_STORE, line("C"))))
                .build();
        PlaceCheckoutCmd copy = Checkouts.valid()
                .eventId(EventId.of(UUID.randomUUID()))
                .occurredAt(Checkouts.NOW.plusSeconds(60))
                .requestedAt(Checkouts.NOW.minusSeconds(600))
                .stores(List.of(store(OTHER_STORE, first.stores().get(1).lines().get(0)), store(STORE, b, a)))
                .build();
        assertThat(ContentHash.of(copy)).isEqualTo(ContentHash.of(first)).hasSize(64);
    }

    @Test
    @DisplayName("any line, quantity, address or customer change gives another hash")
    void changesWithContent() {
        PlaceCheckoutCmd.Line a = line("A");
        String base = ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, a))).build());
        PlaceCheckoutCmd.Line moreOfA = new PlaceCheckoutCmd.Line(a.lineId(), a.productId(), "A", 2);
        assertThat(ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, moreOfA))).build())).isNotEqualTo(base);
        assertThat(ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, a, line("B")))).build()))
                .isNotEqualTo(base);
        PlaceCheckoutCmd.Delivery otherAddress = new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address(
                "2 Le Loi", null, "Ben Nghe", "1", "Ho Chi Minh", "VN", null), "Nguyen Van A", "0901234567");
        assertThat(ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, a))).delivery(otherAddress).build()))
                .isNotEqualTo(base);
        assertThat(ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, a)))
                .customerId(CustomerId.of(UUID.randomUUID())).build())).isNotEqualTo(base);
    }

    private static PlaceCheckoutCmd.Delivery deliveryWithLine1(String line1) {
        return new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address(line1, null, null, null, "X", "VN", null), "N",
                "1");
    }

    @Test
    @DisplayName("NUL and a very long field hash without error; a lone surrogate hashes like '?' (JDK getBytes)")
    void oddText() {
        assertThat(ContentHash.of(Checkouts.valid().delivery(deliveryWithLine1("a\u0000b")).build())).hasSize(64);
        assertThat(ContentHash.of(Checkouts.valid().delivery(deliveryWithLine1("x".repeat(100_000))).build()))
                .hasSize(64);
        // Pinned on purpose: such a command is INVALID_REQUEST, so a re-send with '?' gets that stored answer instead
        // of CONTENT_MISMATCH. Changing ContentHash would change every stored hash.
        List<PlaceCheckoutCmd.Store> stores = List.of(store(STORE, line("A")));
        assertThat(ContentHash.of(Checkouts.valid().stores(stores).delivery(deliveryWithLine1("a\uD800")).build()))
                .isEqualTo(ContentHash.of(Checkouts.valid().stores(stores).delivery(deliveryWithLine1("a?")).build()));
    }

    @Test
    @DisplayName("the lineId is part of the content")
    void lineIdInHash() {
        PlaceCheckoutCmd.Line a = line("A");
        PlaceCheckoutCmd.Line sameButOtherId = new PlaceCheckoutCmd.Line(UUID.randomUUID(), a.productId(), "A",
                a.quantity());
        assertThat(ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, a))).build()))
                .isNotEqualTo(ContentHash.of(Checkouts.valid().stores(List.of(store(STORE, sameButOtherId))).build()));
    }

    @Test
    @DisplayName("fields are length-prefixed: moving text between two fields changes the hash")
    void lengthPrefixed() {
        PlaceCheckoutCmd.Delivery ab = new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address("ab", "c", null, null,
                "X", "VN", null), "N", "1");
        PlaceCheckoutCmd.Delivery a = new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address("a", "bc", null, null,
                "X", "VN", null), "N", "1");
        assertThat(ContentHash.of(Checkouts.valid().delivery(ab).build()))
                .isNotEqualTo(ContentHash.of(Checkouts.valid().delivery(a).build()));
    }
}
