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
        return new OrderQuote.SkuQuote(code, true, PRODUCT, "ACTIVE", "ACTIVE", "VND");
    }

    private static QuoteResult ok(String merchant, boolean open, List<String> reasons, OrderQuote.SkuQuote... skus) {
        return new QuoteResult.Ok(new OrderQuote(merchant, "VND", open, reasons, List.of(skus)));
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
                new OrderQuote.SkuQuote("A", false, null, null, null, null)))).containsExactly(unknownA);
        assertThat(mapper.reasons(store, ok("ACTIVE", true, List.of()))).containsExactly(unknownA);
        assertThat(mapper.reasons(store, ok("ACTIVE", true, List.of(),
                new OrderQuote.SkuQuote("A", true, UUID.randomUUID(), "ACTIVE", "ACTIVE", "VND")))).containsExactly(unknownA);
    }

    @Test
    @DisplayName("SKU or product not ACTIVE → PRODUCT_INACTIVE")
    void productInactive() {
        assertThat(codes(ok("ACTIVE", true, List.of(), new OrderQuote.SkuQuote("A", true, PRODUCT, "INACTIVE",
                "ACTIVE", "VND")))).containsExactly(RejectionCode.PRODUCT_INACTIVE);
        assertThat(codes(ok("ACTIVE", true, List.of(), new OrderQuote.SkuQuote("A", true, PRODUCT, "ACTIVE",
                "DRAFT", "VND")))).containsExactly(RejectionCode.PRODUCT_INACTIVE);
    }

    @Test
    @DisplayName("D3: SKU currency differs from the store's, or the store has none → CURRENCY_MISMATCH")
    void currency() {
        assertThat(codes(ok("ACTIVE", true, List.of(), new OrderQuote.SkuQuote("A", true, PRODUCT, "ACTIVE",
                "ACTIVE", "USD")))).containsExactly(RejectionCode.CURRENCY_MISMATCH);
        assertThat(codes(new QuoteResult.Ok(new OrderQuote("ACTIVE", null, true, List.of(), List.of(sku("A"))))))
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
}
