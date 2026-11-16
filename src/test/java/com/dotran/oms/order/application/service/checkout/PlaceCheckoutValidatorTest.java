package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.config.CheckoutProperties;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.dotran.oms.order.support.Checkouts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.dotran.oms.order.support.Checkouts.NOW;
import static com.dotran.oms.order.support.Checkouts.STORE;
import static com.dotran.oms.order.support.Checkouts.line;
import static com.dotran.oms.order.support.Checkouts.store;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlaceCheckoutValidator")
class PlaceCheckoutValidatorTest {

    private final PlaceCheckoutValidator validator = new PlaceCheckoutValidator(
            new CheckoutProperties(Duration.ofMinutes(10), Duration.ofSeconds(30), Duration.ofSeconds(20)));

    private List<RejectionReason> validate(PlaceCheckoutCmd cmd) {
        return validator.validate(cmd, NOW);
    }

    private static PlaceCheckoutCmd withLines(PlaceCheckoutCmd.Line... lines) {
        return Checkouts.valid().stores(List.of(store(STORE, lines))).build();
    }

    @Test
    @DisplayName("a valid command has no reason")
    void valid() {
        assertThat(validate(Checkouts.valid().build())).isEmpty();
    }

    @Test
    @DisplayName("requestedAt: now + 30 s is accepted, now + 31 s and a missing one are INVALID_REQUEST")
    void requestedAt() {
        assertThat(validate(Checkouts.valid().requestedAt(NOW.plusSeconds(30)).build())).isEmpty();
        assertThat(validate(Checkouts.valid().requestedAt(NOW.plusSeconds(31)).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        assertThat(validate(Checkouts.valid().requestedAt(null).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("0 or 11 stores, a null store or one without storeId: checkout-level INVALID_REQUEST")
    void storesCount() {
        assertThat(validate(Checkouts.valid().stores(List.of()).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        List<PlaceCheckoutCmd.Store> eleven = IntStream.range(0, 11)
                .mapToObj(i -> store(UUID.randomUUID(), line("A"))).toList();
        assertThat(validate(Checkouts.valid().stores(eleven).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        List<PlaceCheckoutCmd.Store> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(validate(Checkouts.valid().stores(withNull).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        assertThat(validate(Checkouts.valid().stores(List.of(store(null, line("A")))).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        assertThat(validate(Checkouts.valid().stores(IntStream.range(0, 10)
                .mapToObj(i -> store(UUID.randomUUID(), line("A"))).toList()).build())).isEmpty();
    }

    @Test
    @DisplayName("a duplicate storeId, or a store without merchant or quote: INVALID_REQUEST on that store")
    void storeFields() {
        assertThat(validate(Checkouts.valid().stores(List.of(store(STORE, line("A")), store(STORE, line("B")))).build()))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        PlaceCheckoutCmd.Store noMerchant = new PlaceCheckoutCmd.Store(null, STORE, UUID.randomUUID(), List.of(line("A")));
        assertThat(validate(Checkouts.valid().stores(List.of(noMerchant)).build()))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
    }

    @Test
    @DisplayName("0 or 101 lines: INVALID_REQUEST on the store; 100 lines are fine")
    void linesCount() {
        assertThat(validate(Checkouts.valid().stores(List.of(new PlaceCheckoutCmd.Store(Checkouts.MERCHANT, STORE,
                UUID.randomUUID(), List.of()))).build()))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        assertThat(validate(withLines(IntStream.range(0, 101).mapToObj(i -> line("S" + i))
                .toArray(PlaceCheckoutCmd.Line[]::new))))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        assertThat(validate(withLines(IntStream.range(0, 100).mapToObj(i -> line("S" + i))
                .toArray(PlaceCheckoutCmd.Line[]::new)))).isEmpty();
    }

    @Test
    @DisplayName("duplicate SKU, quantity 0 or 100, missing lineId or productId: INVALID_REQUEST on the line")
    void lineFields() {
        assertThat(validate(withLines(line("A"), line("A"))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"));
        for (int quantity : new int[]{0, 100}) {
            assertThat(validate(withLines(new PlaceCheckoutCmd.Line(UUID.randomUUID(), Checkouts.PRODUCT, "A",
                    quantity)))).containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"));
        }
        assertThat(validate(withLines(new PlaceCheckoutCmd.Line(UUID.randomUUID(), Checkouts.PRODUCT, "A", 99))))
                .isEmpty();
        assertThat(validate(withLines(new PlaceCheckoutCmd.Line(null, Checkouts.PRODUCT, "A", 1))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"));
        assertThat(validate(withLines(new PlaceCheckoutCmd.Line(UUID.randomUUID(), null, "A", 1))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"));
    }

    @Test
    @DisplayName("a SKU with NUL, over 100 characters or blank: INVALID_REQUEST on the store, the SKU text never in it")
    void unstorableSku() {
        for (String sku : new String[]{"A\u0000B", "X".repeat(101), " ", ""}) {
            List<RejectionReason> reasons = validate(withLines(line(sku)));
            assertThat(reasons).containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
            assertThat(reasons).allSatisfy(r -> assertThat(r.sku()).isNull());
        }
        assertThat(validate(withLines(line("X".repeat(100))))).isEmpty();
    }

    @Test
    @DisplayName("delivery: missing, no address, blank line1/city, bad country, long name or phone → INVALID_REQUEST")
    void delivery() {
        PlaceCheckoutCmd.Address ok = Checkouts.delivery().address();
        List<PlaceCheckoutCmd.Delivery> bad = List.of(
                new PlaceCheckoutCmd.Delivery(null, "N", "1"),
                new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address(" ", null, null, null, "C", "VN", null), "N", "1"),
                new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address("L", null, null, null, null, "VN", null), "N", "1"),
                new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address("L", null, null, null, "C", "vn", null), "N", "1"),
                new PlaceCheckoutCmd.Delivery(ok, "N".repeat(256), "1"),
                new PlaceCheckoutCmd.Delivery(ok, "N", "1".repeat(33)),
                new PlaceCheckoutCmd.Delivery(ok, "N", " "));
        assertThat(validate(Checkouts.valid().delivery(null).build()))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        for (PlaceCheckoutCmd.Delivery d : bad) {
            assertThat(validate(Checkouts.valid().delivery(d).build()))
                    .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
        }
    }

    @Test
    @DisplayName("reasons are distinct and in a fixed order (checkout, then by store and SKU)")
    void normalised() {
        PlaceCheckoutCmd cmd = Checkouts.valid().requestedAt(null).delivery(null).stores(List.of(
                store(STORE, line("B"), line("B"), line("A"), line("A")))).build();
        assertThat(validate(cmd)).containsExactly(
                RejectionReason.checkout(RejectionCode.INVALID_REQUEST),
                RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"),
                RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "B"));
        assertThat(Collections.frequency(validate(cmd), RejectionReason.checkout(RejectionCode.INVALID_REQUEST)))
                .isEqualTo(1);
    }
}
