package com.dotran.oms.order.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

/** The fields of stores-service's order-quote that order-service reads (integration.md, *order-quote*). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderQuoteResponse(Store store, String merchantStatus, Boolean open, List<String> reasons,
                                 List<Sku> skus) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Store(UUID id, String status, String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sku(String sku, boolean found, UUID productId, String status, String productStatus, String currency) {
    }
}
