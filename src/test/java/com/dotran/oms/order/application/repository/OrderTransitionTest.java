package com.dotran.oms.order.application.repository;

import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.OrderStatus;
import com.dotran.oms.order.domain.enums.StockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OrderTransition")
class OrderTransitionTest {

    private static OrderTransition.OrderTransitionBuilder base(OrderStatus from, StockState fromStock, OrderStatus to,
                                                               StockState toStock) {
        return OrderTransition.builder().orderId(OrderId.of(UUID.randomUUID())).merchantId(MerchantId.of(UUID.randomUUID()))
                .checkoutId(CheckoutId.of(UUID.randomUUID())).fromStatus(from).fromStock(fromStock).toStatus(to)
                .toStock(toStock).now(Instant.now());
    }

    @Test
    @DisplayName("entering CONFIRM_SENT/RELEASE_SENT needs a due time; CONFIRMED must not have one")
    void dueRule() {
        assertThatThrownBy(() -> base(OrderStatus.PENDING, StockState.RESERVED, OrderStatus.PLACED,
                StockState.CONFIRM_SENT).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.PLACED,
                StockState.CONFIRMED).stockDueAt(Instant.now()).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a status-only change keeps the stored due time: none may be passed, even in CONFIRM_SENT")
    void statusOnly() {
        assertThatCode(() -> base(OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.CANCELLED,
                StockState.CONFIRM_SENT).build()).doesNotThrowAnyException();
        assertThatThrownBy(() -> base(OrderStatus.PLACED, StockState.CONFIRM_SENT, OrderStatus.CANCELLED,
                StockState.CONFIRM_SENT).stockDueAt(Instant.now()).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
