package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.dotran.oms.order.support.Checkouts.PRODUCT;
import static com.dotran.oms.order.support.Checkouts.STORE;
import static com.dotran.oms.order.support.Checkouts.line;
import static com.dotran.oms.order.support.Checkouts.store;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QuoteReasonMapper")
class QuoteReasonMapperTest {

    private final QuoteReasonMapper mapper = new QuoteReasonMapper();
    private final PlaceCheckoutCmd.Store store = store(STORE, line("A"));

    private static OrderQuote.SkuQuote sku(String code) {
        return new OrderQuote.SkuQuote(code, true, PRODUCT, "ACTIVE", "ACTIVE", "VND", "Tee", "M", new java.math.BigDecimal("25000"));
    }

    private static QuoteResult ok(String merchant, boolean open, List<String> reasons, OrderQuote.SkuQuote... skus) {
        return new QuoteResult.Ok(new OrderQuote(merchant, "VND", open, reasons, List.of(skus), "HCM01", "Asia/Ho_Chi_Minh", java.time.LocalDate.of(2026, 11, 16), null));
    }

    private List<RejectionCode> codes(QuoteResult result) {
        return RejectionReason.normalise(mapper.reasons(store, result)).stream().map(RejectionReason::code).toList();
    }

    @Test
    @DisplayName("open ACTIVE store, ACTIVE merchant, matching SKU: no reason")
    void allGood() {
        assertThat(mapper.reasons(store, ok("ACTIVE", true, List.of(), sku("A")))).isEmpty();
    }

    @Test
    @DisplayName("STORE_NOT_ACTIVE → STORE_INACTIVE; closed reasons → one STORE_CLOSED; both together")
    void storeReasons() {
        assertThat(codes(ok("ACTIVE", false, List.of("STORE_NOT_ACTIVE"), sku("A"))))
                .containsExactly(RejectionCode.STORE_INACTIVE);
        assertThat(codes(ok("ACTIVE", false, List.of("OUTSIDE_HOURS", "CLOSED_WINDOW", "INVALID_SCHEDULE"), sku("A"))))
                .containsExactly(RejectionCode.STORE_CLOSED);
        assertThat(codes(ok("ACTIVE", false, List.of("STORE_NOT_ACTIVE", "OUTSIDE_HOURS"), sku("A"))))
                .containsExactly(RejectionCode.STORE_INACTIVE, RejectionCode.STORE_CLOSED);
        // open=false with a reason we do not know: closed, never open by accident
        assertThat(codes(ok("ACTIVE", false, List.of("SOMETHING_NEW"), sku("A"))))
                .containsExactly(RejectionCode.STORE_CLOSED);
    }

    @Test
    @DisplayName("SUSPENDED merchant → MERCHANT_SUSPENDED")
    void merchantSuspended() {
        assertThat(codes(ok("SUSPENDED", true, List.of(), sku("A")))).containsExactly(RejectionCode.MERCHANT_SUSPENDED);
    }

    @Test
    @DisplayName("found=false, missing from the answer, or productId of another product (D4) → UNKNOWN_SKU on the line")
    void unknownSku() {
        RejectionReason unknownA = RejectionReason.line(RejectionCode.UNKNOWN_SKU, STORE, "A");
        assertThat(mapper.reasons(store, ok("ACTIVE", true, List.of(),
                new OrderQuote.SkuQuote("A", false, null, null, null, null, null, null, null)))).containsExactly(unknownA);
        assertThat(mapper.reasons(store, ok("ACTIVE", true, List.of()))).containsExactly(unknownA);
        assertThat(mapper.reasons(store, ok("ACTIVE", true, List.of(),
                new OrderQuote.SkuQuote("A", true, UUID.randomUUID(), "ACTIVE", "ACTIVE", "VND", "Tee", "M", new java.math.BigDecimal("25000"))))).containsExactly(unknownA);
    }

    @Test
    @DisplayName("SKU or product not ACTIVE → PRODUCT_INACTIVE")
    void productInactive() {
        assertThat(codes(ok("ACTIVE", true, List.of(), new OrderQuote.SkuQuote("A", true, PRODUCT, "INACTIVE",
                "ACTIVE", "VND", "Tee", "M", new java.math.BigDecimal("25000"))))).containsExactly(RejectionCode.PRODUCT_INACTIVE);
        assertThat(codes(ok("ACTIVE", true, List.of(), new OrderQuote.SkuQuote("A", true, PRODUCT, "ACTIVE",
                "DRAFT", "VND", "Tee", "M", new java.math.BigDecimal("25000"))))).containsExactly(RejectionCode.PRODUCT_INACTIVE);
    }

    @Test
    @DisplayName("D3: SKU currency differs from the store's, or the store has none → CURRENCY_MISMATCH")
    void currency() {
        assertThat(codes(ok("ACTIVE", true, List.of(), new OrderQuote.SkuQuote("A", true, PRODUCT, "ACTIVE",
                "ACTIVE", "USD", "Tee", "M", new java.math.BigDecimal("25000"))))).containsExactly(RejectionCode.CURRENCY_MISMATCH);
        assertThat(codes(new QuoteResult.Ok(new OrderQuote("ACTIVE", null, true, List.of(), List.of(sku("A")), "HCM01", "Asia/Ho_Chi_Minh", java.time.LocalDate.of(2026, 11, 16), null))))
                .containsExactly(RejectionCode.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("404 and 400 → INVALID_REQUEST on the store; unavailable → STORE_UNAVAILABLE")
    void callFailures() {
        assertThat(mapper.reasons(store, new QuoteResult.NotFound()))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        assertThat(mapper.reasons(store, new QuoteResult.BadRequest()))
                .containsExactly(RejectionReason.store(RejectionCode.INVALID_REQUEST, STORE));
        assertThat(mapper.reasons(store, new QuoteResult.Unavailable("x")))
                .containsExactly(RejectionReason.store(RejectionCode.STORE_UNAVAILABLE, STORE));
    }

    private static QuoteResult quote(String currency, String code, java.time.LocalDate day, OrderQuote.SkuQuote sku) {
        return new QuoteResult.Ok(new OrderQuote("ACTIVE", currency, true, List.of(), List.of(sku), code,
                "Asia/Ho_Chi_Minh", day, null));
    }

    private static OrderQuote.SkuQuote priced(String currency, String price, String name) {
        return new OrderQuote.SkuQuote("A", true, PRODUCT, "ACTIVE", "ACTIVE", currency, name, "M",
                price == null ? null : new java.math.BigDecimal(price));
    }

    @Test
    @DisplayName("D7: a currency not ISO 4217 or without minor units → CURRENCY_MISMATCH per line")
    void unusableCurrency() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 16);
        assertThat(codes(quote("XYZ", "HCM01", day, priced("XYZ", "1", "Tee"))))
                .containsExactly(RejectionCode.CURRENCY_MISMATCH);
        assertThat(codes(quote("XAU", "HCM01", day, priced("XAU", "1", "Tee"))))
                .containsExactly(RejectionCode.CURRENCY_MISMATCH);
        assertThat(codes(quote("USD", "HCM01", day, priced("USD", "1", "Tee")))).isEmpty();
    }

    @Test
    @DisplayName("no local day → STORE_CLOSED; a store code outside [A-Z0-9]{1,20} → STORE_UNAVAILABLE (D4)")
    void dayAndCode() {
        assertThat(codes(quote("VND", "HCM01", null, priced("VND", "1", "Tee"))))
                .containsExactly(RejectionCode.STORE_CLOSED);
        assertThat(codes(quote("VND", "q-1", java.time.LocalDate.of(2026, 11, 16), priced("VND", "1", "Tee"))))
                .containsExactly(RejectionCode.STORE_UNAVAILABLE);
        assertThat(codes(quote("VND", null, java.time.LocalDate.of(2026, 11, 16), priced("VND", "1", "Tee"))))
                .containsExactly(RejectionCode.STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Q1: a null or negative price, or no product name → STORE_UNAVAILABLE; price 0 is accepted")
    void price() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 16);
        assertThat(codes(quote("VND", "HCM01", day, priced("VND", null, "Tee"))))
                .containsExactly(RejectionCode.STORE_UNAVAILABLE);
        assertThat(codes(quote("VND", "HCM01", day, priced("VND", "-1", "Tee"))))
                .containsExactly(RejectionCode.STORE_UNAVAILABLE);
        assertThat(codes(quote("VND", "HCM01", day, priced("VND", "1", null))))
                .containsExactly(RejectionCode.STORE_UNAVAILABLE);
        assertThat(codes(quote("VND", "HCM01", day, priced("VND", "0", "Tee")))).isEmpty();
    }
}
