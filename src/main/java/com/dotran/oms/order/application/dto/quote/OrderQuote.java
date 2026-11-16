package com.dotran.oms.order.application.dto.quote;

import java.util.List;
import java.util.UUID;

/**
 * What order-service reads from stores-service's order-quote (oms-workspace integration.md, *order-quote*). Statuses stay
 * strings: they are stores-service's vocabulary, mapped by {@code QuoteReasonMapper}.
 */
public record OrderQuote(String merchantStatus, String storeCurrency, boolean open, List<String> reasons,
                         List<SkuQuote> skus) {

    public OrderQuote {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        skus = skus == null ? List.of() : List.copyOf(skus);
    }

    public record SkuQuote(String sku, boolean found, UUID productId, String status, String productStatus,
                           String currency) {
    }
}
