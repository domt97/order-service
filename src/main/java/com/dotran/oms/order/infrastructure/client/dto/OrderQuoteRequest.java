package com.dotran.oms.order.infrastructure.client.dto;

import java.util.List;

/** Body of stores-service's order-quote: 0..100 SKU codes. */
public record OrderQuoteRequest(List<String> skus) {
}
