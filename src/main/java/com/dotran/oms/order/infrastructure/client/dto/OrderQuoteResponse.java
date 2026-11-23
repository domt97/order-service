package com.dotran.oms.order.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The fields of stores-service's order-quote that order-service reads (integration.md, *order-quote*). A malformed
 * {@code localDate} fails the body read, which the client answers as Unavailable (STORE_UNAVAILABLE).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderQuoteResponse(Store store, String merchantStatus, LocalDate localDate, Boolean open,
                                 List<String> reasons, List<Sku> skus) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Store(UUID id, String status, String currency, String code, String timeZone,
                        Integer maxOrdersPerDay) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sku(String sku, boolean found, UUID productId, String status, String productStatus, String currency,
                      String productName, String skuName, BigDecimal price) {
    }
}
