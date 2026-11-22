package com.dotran.oms.order.application.dto.quote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What order-service reads from stores-service's order-quote (oms-workspace integration.md, *order-quote*). Statuses stay
 * strings: they are stores-service's vocabulary, mapped by {@code QuoteReasonMapper}. The placement fields (code, local
 * day, daily cap, names, prices) are snapshotted onto the orders; {@code localDate} is null when the store's time zone
 * cannot be read.
 */
public record OrderQuote(String merchantStatus, String storeCurrency, boolean open, List<String> reasons,
                         List<SkuQuote> skus, String storeCode, String timeZone, LocalDate localDate,
                         Integer maxOrdersPerDay) {

    public OrderQuote {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        skus = skus == null ? List.of() : List.copyOf(skus);
    }

    /** Product fields are null when {@code found = false}. */
    public record SkuQuote(String sku, boolean found, UUID productId, String status, String productStatus,
                           String currency, String productName, String skuName, BigDecimal price) {
    }
}
