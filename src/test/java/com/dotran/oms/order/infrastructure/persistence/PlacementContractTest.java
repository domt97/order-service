package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.event.StockCommandFactory;
import com.dotran.oms.order.common.id.CartId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.CheckoutStatus;
import com.dotran.oms.order.domain.model.Checkout;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;
import com.dotran.oms.order.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.dotran.oms.order.support.Checkouts.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ReserveStock and CheckoutPlaced as Debezium publishes them (OutboxPayloadSerializer) against the copied contracts. */
@DisplayName("ReserveStock / CheckoutPlaced contracts")
class PlacementContractTest {

    private static final DeliverySnapshot DELIVERY = new DeliverySnapshot("Nguyen Van A", "0901234567", "1 Le Loi",
            null, "Ben Nghe", "1", "Ho Chi Minh", null, "VN");

    private final OutboxPayloadSerializer serializer = new OutboxPayloadSerializer();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(OutboxMessage message) throws Exception {
        return mapper.readTree(serializer.toJson(message.payload()));
    }

    private static Order order(CheckoutId checkout, int lines) {
        return Order.pending(checkout, CustomerId.of(UUID.randomUUID()), MerchantId.of(UUID.randomUUID()),
                StoreId.of(UUID.randomUUID()), "HCM01", LocalDate.of(2026, 11, 21), "VND", UUID.randomUUID(),
                DELIVERY, IntStream.range(0, lines).mapToObj(i -> OrderLine.snapshot(UUID.randomUUID(),
                        ProductId.of(UUID.randomUUID()), "S".repeat(90) + i, "Tee", null, 99,
                        new BigDecimal("25000"), "VND", 0)).toList(), NOW);
    }

    private static Order numbered(Order o, String number) {
        return new Order(o.id(), o.checkoutId(), o.customerId(), o.merchantId(), o.storeId(), o.storeCode(),
                o.localDate(), 1, number, com.dotran.oms.order.domain.enums.OrderStatus.PLACED,
                com.dotran.oms.order.domain.enums.StockState.CONFIRM_SENT, null, null, NOW.plusSeconds(150), 0, null,
                null, o.currency(), o.itemsTotal(), o.deliveryQuoteId(), null, null, o.delivery(), o.lines(), NOW,
                null, o.createdAt(), NOW);
    }

    @Test
    @DisplayName("ReserveStock of a 100-line order validates, is merchant-scoped, keyed by orderId, no PII")
    void reserve() throws Exception {
        Order o = order(CheckoutId.newId(), 100);
        OutboxMessage message = new StockCommandFactory().reserve(o, NOW);
        JsonNode payload = json(message);
        assertThat(ContractSchemas.validate("ReserveStock", payload)).isEmpty();
        assertThat(message.merchantId()).isEqualTo(o.merchantId().getValue());
        assertThat(message.customerId()).isNull();
        assertThat(message.aggregateId()).isEqualTo(o.id().getValue());
        assertThat(payload.get("lines")).hasSize(100);
        assertThat(payload.get("lines").get(0).get("orderItemId").asText())
                .isEqualTo(o.lines().get(0).id().getValue().toString());
        assertThat(payload.toString()).doesNotContain("Nguyen", "0901234567", "Le Loi", "25000");
    }

    @Test
    @DisplayName("CheckoutPlaced validates, keyed by cartId, customer-scoped, no prices or PII; unnumbered orders refused")
    void placed() throws Exception {
        Checkout checkout = new Checkout(CheckoutId.newId(), CustomerId.of(UUID.randomUUID()),
                CartId.of(UUID.randomUUID()), 3, CheckoutStatus.PLACED, "a".repeat(64), null,
                EventId.of(UUID.randomUUID()), NOW, NOW, NOW.plusSeconds(150), NOW, NOW);
        Order a = numbered(order(checkout.id(), 1), "HCM01-261121-001");
        Order b = numbered(order(checkout.id(), 2), "HCM01-261121-002");
        EventId cause = EventId.of(UUID.randomUUID());
        OutboxMessage message = new CheckoutReplyFactory().placed(checkout, List.of(a, b), cause, NOW);
        JsonNode payload = json(message);
        assertThat(ContractSchemas.validate("CheckoutPlaced", payload)).isEmpty();
        assertThat(message.aggregateId()).isEqualTo(checkout.cartId().getValue());
        assertThat(message.customerId()).isEqualTo(checkout.customerId().getValue());
        assertThat(message.merchantId()).isNull();
        assertThat(payload.get("causationId").asText()).isEqualTo(cause.getValue().toString());
        assertThat(payload.get("orders")).hasSize(2);
        assertThat(payload.toString()).doesNotContain("Nguyen", "0901234567", "25000");
        assertThatThrownBy(() -> new CheckoutReplyFactory().placed(checkout, List.of(order(checkout.id(), 1)), cause,
                NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ConfirmStockReservation and ReleaseStockReservation validate, merchant-scoped, keyed by orderId")
    void confirmAndRelease() throws Exception {
        Order o = order(CheckoutId.newId(), 1);
        StockCommandFactory factory = new StockCommandFactory();
        OutboxMessage confirm = factory.confirm(o, NOW);
        assertThat(ContractSchemas.validate("ConfirmStockReservation", json(confirm))).isEmpty();
        OutboxMessage release = factory.release(o, true, "STOCK_NOT_CONFIRMED", NOW);
        JsonNode payload = json(release);
        assertThat(ContractSchemas.validate("ReleaseStockReservation", payload)).isEmpty();
        assertThat(payload.get("restock").asBoolean()).isTrue();
        for (OutboxMessage m : List.of(confirm, release)) {
            assertThat(m.merchantId()).isEqualTo(o.merchantId().getValue());
            assertThat(m.aggregateId()).isEqualTo(o.id().getValue());
        }
        assertThatThrownBy(() -> factory.release(o, true, "R".repeat(51), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
