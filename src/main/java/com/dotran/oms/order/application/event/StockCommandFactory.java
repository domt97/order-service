package com.dotran.oms.order.application.event;

import com.dotran.oms.order.domain.model.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the stock commands to inventory-service (ADR 0006) as outbox rows: always a new eventId; merchant-scoped
 * (V1 CHECK, system rule 4); topic = command name, key = orderId (system rule 8).
 */
@Component
public class StockCommandFactory {

    public static final String AGGREGATE_TYPE = "Order";

    public OutboxMessage reserve(Order order, Instant now) {
        UUID eventId = UUID.randomUUID();
        ReserveStockCommand payload = new ReserveStockCommand(eventId, ReserveStockCommand.TYPE, now,
                order.merchantId().getValue(), order.storeId().getValue(), order.id().getValue(),
                order.lines().stream().map(l -> new ReserveStockCommand.Line(l.id().getValue(),
                        l.productId().getValue(), l.sku(), l.quantity())).toList());
        return new OutboxMessage(eventId, null, order.merchantId().getValue(), AGGREGATE_TYPE, order.id().getValue(),
                ReserveStockCommand.TYPE, payload);
    }

    public OutboxMessage confirm(Order order, Instant now) {
        UUID eventId = UUID.randomUUID();
        return new OutboxMessage(eventId, null, order.merchantId().getValue(), AGGREGATE_TYPE, order.id().getValue(),
                ConfirmStockReservationCommand.TYPE, new ConfirmStockReservationCommand(eventId,
                ConfirmStockReservationCommand.TYPE, now, order.merchantId().getValue(), order.storeId().getValue(),
                order.id().getValue()));
    }

    /** {@code reason} ≤ 50 characters (contract); restock and reason are stored on the order for every re-send. */
    public OutboxMessage release(Order order, boolean restock, String reason, Instant now) {
        if (reason == null || reason.isEmpty() || reason.length() > 50) {
            throw new IllegalArgumentException("release reason must be 1..50 characters");
        }
        UUID eventId = UUID.randomUUID();
        return new OutboxMessage(eventId, null, order.merchantId().getValue(), AGGREGATE_TYPE, order.id().getValue(),
                ReleaseStockReservationCommand.TYPE, new ReleaseStockReservationCommand(eventId,
                ReleaseStockReservationCommand.TYPE, now, order.merchantId().getValue(), order.storeId().getValue(),
                order.id().getValue(), restock, reason));
    }
}
