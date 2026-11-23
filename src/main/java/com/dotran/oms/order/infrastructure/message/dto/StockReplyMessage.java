package com.dotran.oms.order.infrastructure.message.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * External DTO (anti-corruption layer) of inventory-service's five stock replies (oms-workspace contracts/events
 * StockReserved, StockReservationRejected, StockReservationConfirmed, StockReservationConfirmationFailed,
 * StockReservationReleased). One shape for all: each reply uses the fields it has. Ids and instants stay text and are
 * parsed by {@code StockReplyMapping}; unknown fields are ignored (additive changes stay compatible).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockReplyMessage(String eventId, String eventType, String occurredAt, String merchantId,
                                String storeId, String orderId, String causationId, String expiresAt,
                                String orderReason, String reason, Boolean restock, List<Line> lines) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String orderItemId, String sku, BigDecimal quantity, String reason, String status) {
    }
}
