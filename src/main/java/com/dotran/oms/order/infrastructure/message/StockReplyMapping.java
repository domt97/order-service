package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.application.service.stock.InvalidStockReplyException;
import com.dotran.oms.order.infrastructure.message.dto.StockReplyMessage;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Maps a stock reply to its command. The record must be the topic's type and carry valid ids; otherwise it cannot be
 * applied to any order and goes to the DLT ({@link InvalidStockReplyException}, message names the field only).
 */
@UtilityClass
class StockReplyMapping {

    StockReplyCmd toCmd(String topic, StockReplyCmd.Type type, StockReplyMessage m) {
        if (!topic.equals(m.eventType())) {
            throw new InvalidStockReplyException("eventType is not " + topic);
        }
        List<StockReplyCmd.Line> lines = m.lines() == null ? List.of() : m.lines().stream()
                .map(l -> {
                    if (l == null) {
                        throw new InvalidStockReplyException("lines: null entry");
                    }
                    return new StockReplyCmd.Line(OrderItemId.of(uuid(l.orderItemId(), "lines.orderItemId")),
                            l.sku(), quantity(l.quantity()), l.reason());
                })
                .toList();
        Instant expiresAt = type == StockReplyCmd.Type.RESERVED ? instant(m.expiresAt(), "expiresAt") : null;
        return new StockReplyCmd(type, EventId.of(uuid(m.eventId(), "eventId")),
                m.causationId() == null ? null : uuid(m.causationId(), "causationId"),
                MerchantId.of(uuid(m.merchantId(), "merchantId")), StoreId.of(uuid(m.storeId(), "storeId")),
                OrderId.of(uuid(m.orderId(), "orderId")), expiresAt, m.orderReason(), m.reason(), lines);
    }

    private static UUID uuid(String value, String field) {
        if (value == null) {
            throw new InvalidStockReplyException(field + " missing");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidStockReplyException(field + " not a uuid");
        }
    }

    private static Instant instant(String value, String field) {
        if (value == null) {
            throw new InvalidStockReplyException(field + " missing");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidStockReplyException(field + " not an instant");
        }
    }

    private static Integer quantity(BigDecimal value) {
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidStockReplyException("lines.quantity not a whole number");
        }
    }
}
