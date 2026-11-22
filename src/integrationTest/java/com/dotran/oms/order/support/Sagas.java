package com.dotran.oms.order.support;

import com.dotran.oms.order.application.service.checkout.CheckoutTransactions;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.PlacementPlan;
import com.dotran.oms.order.domain.valueobject.StoreOrder;
import com.dotran.oms.order.infrastructure.message.StockReplyConsumers;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** IT helpers: a placed checkout (PENDING, ReserveStock sent) and inventory reply records for its orders. */
public final class Sagas {

    private Sagas() {
    }

    public record Placed(Checkout checkout, List<Order> orders) {
        public Order order(UUID store) {
            return orders.stream().filter(o -> o.storeId().getValue().equals(store)).findFirst().orElseThrow();
        }
    }

    public static Placed place(CheckoutTransactions transactions, UUID customer, UUID merchant, UUID... stores) {
        Checkout checkout = Orders.pendingCheckout(customer, Orders.now());
        List<StoreOrder> storeOrders = java.util.Arrays.stream(stores).map(store -> new StoreOrder(Orders.pending(
                checkout.id(), customer, merchant, store, checkout.createdAt(),
                Orders.line("TS-M", "10000", 2), Orders.line("TS-L", "12000", 1)), null)).toList();
        PlacementPlan plan = new PlacementPlan(checkout, storeOrders);
        transactions.place(plan, checkout.createdAt());
        return new Placed(checkout, plan.orders());
    }

    private static String head(String type, Order o) {
        return """
                "eventId":"%s","eventType":"%s","occurredAt":"2026-11-21T03:00:00Z","merchantId":"%s","storeId":"%s",
                "orderId":"%s","causationId":"%s"
                """.formatted(UUID.randomUUID(), type, o.merchantId().getValue(), o.storeId().getValue(),
                o.id().getValue(), UUID.randomUUID());
    }

    public static String reserved(Order o) {
        return "{" + head("StockReserved", o) + ",\"expiresAt\":\"2026-11-21T03:15:00Z\",\"lines\":["
                + o.lines().stream().map(l -> """
                        {"orderItemId":"%s","sku":"%s","quantity":%d,"expiresAt":"2026-11-21T03:15:00Z"}"""
                        .formatted(l.id().getValue(), l.sku(), l.quantity())).collect(Collectors.joining(","))
                + "]}";
    }

    public static String rejected(Order o, String orderReason, String lineReason) {
        OrderLine first = o.lines().get(0);
        String lines = lineReason == null ? "" : """
                {"orderItemId":"%s","sku":"%s","reason":"%s"}""".formatted(first.id().getValue(), first.sku(),
                lineReason);
        return "{" + head("StockReservationRejected", o) + ",\"orderReason\":"
                + (orderReason == null ? "null" : "\"" + orderReason + "\"") + ",\"lines\":[" + lines + "]}";
    }

    public static String confirmed(Order o) {
        return "{" + head("StockReservationConfirmed", o) + ",\"lines\":[" + o.lines().stream()
                .map(l -> "{\"orderItemId\":\"" + l.id().getValue() + "\",\"status\":\"CONFIRMED\"}")
                .collect(Collectors.joining(",")) + "]}";
    }

    public static String confirmationFailed(Order o, String reason) {
        return "{" + head("StockReservationConfirmationFailed", o) + ",\"reason\":\"" + reason + "\",\"lines\":[]}";
    }

    public static String released(Order o) {
        return "{" + head("StockReservationReleased", o) + ",\"restock\":true,\"lines\":[]}";
    }

    /** Delivers one reply record to the listener method of its topic. */
    public static void deliver(StockReplyConsumers consumers, String json) throws Exception {
        String type = json.replaceAll("(?s).*\"eventType\":\"([A-Za-z]+)\".*", "$1");
        String key = json.replaceAll("(?s).*\"orderId\":\"([0-9a-f-]+)\".*", "$1");
        ConsumerRecord<String, String> record = new ConsumerRecord<>(type, 0, 0L, key, json);
        switch (type) {
            case "StockReserved" -> consumers.reserved(record);
            case "StockReservationRejected" -> consumers.rejected(record);
            case "StockReservationConfirmed" -> consumers.confirmed(record);
            case "StockReservationConfirmationFailed" -> consumers.confirmationFailed(record);
            case "StockReservationReleased" -> consumers.released(record);
            default -> throw new IllegalArgumentException(type);
        }
    }
}
