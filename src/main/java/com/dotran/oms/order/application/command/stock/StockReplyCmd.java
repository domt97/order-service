package com.dotran.oms.order.application.command.stock;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One reply of inventory-service to a stock command (ADR 0006), after the anti-corruption mapping. The ids are always
 * set; a record without them never becomes a command (it goes to the DLT). {@code causationId} is only logged: a reply
 * is deduplicated by the order's state, never by it (ADR 0006 obligation 4 as amended).
 */
public record StockReplyCmd(Type type, EventId eventId, UUID causationId, MerchantId merchantId, StoreId storeId,
                            OrderId orderId, Instant expiresAt, String orderReason, String failureReason,
                            List<Line> lines) {

    public StockReplyCmd {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(orderId, "orderId");
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public enum Type {
        RESERVED, RESERVATION_REJECTED, CONFIRMED, CONFIRMATION_FAILED, RELEASED
    }

    /** {@code sku} and {@code quantity} for RESERVED, {@code sku} and {@code reason} for a rejection, else id only. */
    public record Line(OrderItemId orderItemId, String sku, Integer quantity, String reason) {

        public Line {
            Objects.requireNonNull(orderItemId, "orderItemId");
        }
    }
}
