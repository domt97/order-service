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
    @DisplayName("a SKU with NUL, over 100 code points or blank: INVALID_REQUEST on the store, the SKU text never in it")
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

    private static final UUID OTHER_STORE = UUID.fromString("00000000-0000-0000-0000-00000000b002");
    private static final String EMOJI = "\uD83D\uDE00";

    private static PlaceCheckoutCmd.Line lineWithId(UUID lineId, String sku) {
        return new PlaceCheckoutCmd.Line(lineId, Checkouts.PRODUCT, sku, 1);
    }

    private List<RejectionReason> withStores(PlaceCheckoutCmd.Store... stores) {
        return validator.validate(Checkouts.valid().stores(List.of(stores)).build(), NOW);
    }

    @Test
    @DisplayName("a lineId used twice: INVALID_REQUEST on the later line, in the same store or another one")
    void duplicateLineId() {
        UUID id = UUID.randomUUID();
        assertThat(withStores(store(STORE, lineWithId(id, "A"), lineWithId(id, "B"))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "B"));
        assertThat(withStores(store(STORE, lineWithId(id, "A")), store(OTHER_STORE, lineWithId(id, "A"))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, OTHER_STORE, "A"));
        assertThat(withStores(store(STORE, lineWithId(id, "A")), store(OTHER_STORE, lineWithId(id, "B"))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, OTHER_STORE, "B"));
        assertThat(withStores(store(STORE, lineWithId(id, "A"), lineWithId(id, "B"), lineWithId(id, "C")))).containsExactly(
                RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "B"),
                RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "C"));
        // same lineId and same SKU on one line: one reason
        assertThat(withStores(store(STORE, lineWithId(id, "A"), lineWithId(id, "A"))))
                .containsExactly(RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"));
        // null lineIds are reported as missing, never as duplicates of each other
        assertThat(withStores(store(STORE, lineWithId(null, "A"), lineWithId(null, "B")))).containsExactly(
                RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "A"),
                RejectionReason.line(RejectionCode.INVALID_REQUEST, STORE, "B"));
    }

    @Test
    @DisplayName("a line with a bad SKU still claims its lineId; a bad-SKU line reusing one gets only the store reason")
    void duplicateLineIdWithBadSku() {
        UUID id = UUID.randomUUID();
        assertThat(withStores(store(STORE, lineWithId(id, "A\u0000")), store(OTHER_STORE, lineWithId(id, "B")))).containsExactly(
                RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE),
                RejectionReason.line(RejectionCode.INVALID_REQUEST, OTHER_STORE, "B"));
        List<RejectionReason> reverse = withStores(store(STORE, lineWithId(id, "A"), lineWithId(id, "B\u0000")));
        assertThat(reverse).containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        assertThat(reverse).allSatisfy(r -> assertThat(r.sku()).isNull());
    }

    @Test
    @DisplayName("a null line (\"lines\":[null]) or a null SKU: INVALID_REQUEST on the store; a null-SKU line still claims its lineId")
    void nullLineAndSku() {
        List<PlaceCheckoutCmd.Line> withNull = new ArrayList<>();
        withNull.add(null);
        assertThat(withStores(new PlaceCheckoutCmd.Store(Checkouts.MERCHANT, STORE, UUID.randomUUID(), withNull)))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        UUID id = UUID.randomUUID();
        assertThat(withStores(store(STORE, lineWithId(id, null)), store(OTHER_STORE, lineWithId(id, "B"))))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE),
                        RejectionReason.line(RejectionCode.INVALID_REQUEST, OTHER_STORE, "B"));
    }

    @Test
    @DisplayName("a store without storeId is not walked: its lineIds are not claimed, only the checkout-level reason")
    void nullStoreIdClaimsNothing() {
        UUID id = UUID.randomUUID();
        assertThat(withStores(store(null, lineWithId(id, "A")), store(OTHER_STORE, lineWithId(id, "A"))))
                .containsExactly(RejectionReason.checkout(RejectionCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("SKU: 100 code points (emoji) fine, 101 not; unpaired surrogates and Unicode blanks on the store")
    void skuCodePoints() {
        assertThat(validate(withLines(line(EMOJI.repeat(100))))).isEmpty();
        for (String sku : new String[]{EMOJI.repeat(101), "A\uD800", "\uDC00", "\uDC00\uD800", "\u3000", "\u001F"}) {
            List<RejectionReason> reasons = validate(withLines(line(sku)));
            assertThat(reasons).as(sku).containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
            assertThat(reasons).allSatisfy(r -> assertThat(r.sku()).isNull());
        }
        assertThat(validate(withLines(line("\u00A0")))).isEmpty();
    }

    /** One delivery with field {@code index} (0 line1 … 5 postalCode, 6 name, 7 phone) set to {@code value}. */
    private static PlaceCheckoutCmd.Delivery deliveryWith(int index, String value) {
        String[] f = {"1 Le Loi", null, "Ben Nghe", "1", "Ho Chi Minh", null, "Nguyen Van A", "0901234567"};
        f[index] = value;
        return new PlaceCheckoutCmd.Delivery(new PlaceCheckoutCmd.Address(f[0], f[1], f[2], f[3], f[4], "VN", f[5]),
                f[6], f[7]);
    }

    private static final int[] MAX = {255, 255, 100, 100, 100, 20, 255, 32};
    private static final boolean[] REQUIRED = {true, false, false, false, true, false, true, true};

    private List<RejectionReason> withDelivery(PlaceCheckoutCmd.Delivery d) {
        return validate(Checkouts.valid().delivery(d).build());
    }

    @Test
    @DisplayName("each delivery field: at its cap fine, one over not; an emoji counts as one; NUL or a lone half not")
    void deliveryLimits() {
        RejectionReason invalid = RejectionReason.checkout(RejectionCode.INVALID_REQUEST);
        for (int i = 0; i < MAX.length; i++) {
            assertThat(withDelivery(deliveryWith(i, "a".repeat(MAX[i])))).as("field %d at cap", i).isEmpty();
            assertThat(withDelivery(deliveryWith(i, EMOJI.repeat(MAX[i])))).as("field %d emoji at cap", i).isEmpty();
            assertThat(withDelivery(deliveryWith(i, "a".repeat(MAX[i] + 1)))).as("field %d over", i)
                    .containsExactly(invalid);
            for (String bad : new String[]{"a\u0000b", "a\uD800", "\uDC00", "\uDC00\uD800"}) {
                List<RejectionReason> reasons = withDelivery(deliveryWith(i, bad));
                assertThat(reasons).as("field %d", i).containsExactly(invalid);
                assertThat(reasons).allSatisfy(r -> assertThat(r.sku()).isNull());
            }
        }
    }

    @Test
    @DisplayName("blank is Character.isWhitespace: U+3000 or U+001F alone is blank, U+00A0 is not; optional may be empty")
    void deliveryBlank() {
        RejectionReason invalid = RejectionReason.checkout(RejectionCode.INVALID_REQUEST);
        for (int i = 0; i < MAX.length; i++) {
            if (REQUIRED[i]) {
                for (String blank : new String[]{"", " ", "\u3000", "\u001F", "\t\u2028"}) {
                    assertThat(withDelivery(deliveryWith(i, blank))).as("field %d blank", i).containsExactly(invalid);
                }
                assertThat(withDelivery(deliveryWith(i, null))).as("field %d null", i).containsExactly(invalid);
                assertThat(withDelivery(deliveryWith(i, "\u00A0"))).as("field %d nbsp", i).isEmpty();
            } else {
                assertThat(withDelivery(deliveryWith(i, null))).as("field %d null", i).isEmpty();
                assertThat(withDelivery(deliveryWith(i, ""))).as("field %d empty", i).isEmpty();
                assertThat(withDelivery(deliveryWith(i, " "))).as("field %d space", i).isEmpty();
            }
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
