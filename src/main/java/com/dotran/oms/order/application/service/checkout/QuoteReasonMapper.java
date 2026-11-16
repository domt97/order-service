package com.dotran.oms.order.application.service.checkout;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.dto.quote.OrderQuote;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns one store's order quote into rejection reasons (BR-ORD-02; the order-quote mapping decided 2026-11-15 in
 * integration.md; D3 and D4 decided 2026-11-16). Daily cap and delivery fee are checked at placement (next PR).
 */
@Component
public class QuoteReasonMapper {

    private static final Set<String> CLOSED_REASONS = Set.of("OUTSIDE_HOURS", "CLOSED_WINDOW", "INVALID_SCHEDULE");
    private static final String ACTIVE = "ACTIVE";

    public List<RejectionReason> reasons(PlaceCheckoutCmd.Store store, QuoteResult result) {
        UUID storeId = store.storeId();
        // Java 17: no pattern matching in switch.
        if (result instanceof QuoteResult.Ok ok) {
            return fromQuote(store, ok.quote());
        }
        if (result instanceof QuoteResult.NotFound || result instanceof QuoteResult.BadRequest) {
            return List.of(RejectionReason.store(RejectionCode.INVALID_REQUEST, storeId));
        }
        return List.of(RejectionReason.store(RejectionCode.STORE_UNAVAILABLE, storeId));
    }

    private static List<RejectionReason> fromQuote(PlaceCheckoutCmd.Store store, OrderQuote quote) {
        UUID storeId = store.storeId();
        List<RejectionReason> reasons = new ArrayList<>();
        if ("SUSPENDED".equals(quote.merchantStatus())) {
            reasons.add(RejectionReason.store(RejectionCode.MERCHANT_SUSPENDED, storeId));
        }
        if (quote.reasons().contains("STORE_NOT_ACTIVE")) {
            reasons.add(RejectionReason.store(RejectionCode.STORE_INACTIVE, storeId));
        }
        boolean closedReason = quote.reasons().stream().anyMatch(CLOSED_REASONS::contains);
        // Not open with no reason we know of: closed, never open by accident.
        boolean unknownReason = !quote.open() && quote.reasons().stream()
                .noneMatch(r -> CLOSED_REASONS.contains(r) || "STORE_NOT_ACTIVE".equals(r));
        if (closedReason || unknownReason) {
            reasons.add(RejectionReason.store(RejectionCode.STORE_CLOSED, storeId));
        }
        Map<String, OrderQuote.SkuQuote> bySku = quote.skus().stream()
                .filter(s -> s.sku() != null)
                .collect(Collectors.toMap(OrderQuote.SkuQuote::sku, Function.identity(), (a, b) -> a));
        for (PlaceCheckoutCmd.Line line : store.lines()) {
            OrderQuote.SkuQuote sku = bySku.get(line.sku());
            if (sku == null || !sku.found() || !Objects.equals(sku.productId(), line.productId())) {
                // D4: a SKU that now belongs to another product is not the item the customer chose.
                reasons.add(RejectionReason.line(RejectionCode.UNKNOWN_SKU, storeId, line.sku()));
                continue;
            }
            if (!ACTIVE.equals(sku.status()) || !ACTIVE.equals(sku.productStatus())) {
                reasons.add(RejectionReason.line(RejectionCode.PRODUCT_INACTIVE, storeId, line.sku()));
            }
            // D3: a SKU is priced in its store's currency; a store without one cannot sell.
            if (quote.storeCurrency() == null || !quote.storeCurrency().equals(sku.currency())) {
                reasons.add(RejectionReason.line(RejectionCode.CURRENCY_MISMATCH, storeId, line.sku()));
            }
        }
        return reasons;
    }
}
