package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reply's lines must belong to the order (StockSagaTransactions.checkLines). */
class StockReplyLinesTest {

    private final Order order = CheckoutDecisionTest.order(StockState.RESERVE_SENT, null);
    private final OrderLine line = order.lines().get(0);

    private StockReplyCmd reply(StockReplyCmd.Type type, StockReplyCmd.Line... lines) {
        return new StockReplyCmd(type, EventId.of(UUID.randomUUID()), null, order.merchantId(), order.storeId(),
                order.id(), null, null, null, List.of(lines));
    }

    @Test
    @DisplayName("StockReserved must list exactly the order's lines with SKU and quantity")
    void reserved() {
        assertThatCode(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RESERVED,
                new StockReplyCmd.Line(line.id(), "TS-M", 1, null)), order)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RESERVED), order))
                .isInstanceOf(InvalidStockReplyException.class);
        assertThatThrownBy(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RESERVED,
                new StockReplyCmd.Line(line.id(), "TS-M", 2, null)), order))
                .isInstanceOf(InvalidStockReplyException.class);
        assertThatThrownBy(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RESERVED,
                new StockReplyCmd.Line(line.id(), "OTHER", 1, null)), order))
                .isInstanceOf(InvalidStockReplyException.class);
    }

    @Test
    @DisplayName("other replies: a subset by id; a line of another order or a repeated id is refused")
    void subset() {
        assertThatCode(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RELEASED), order))
                .doesNotThrowAnyException();
        assertThatCode(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.CONFIRMED,
                new StockReplyCmd.Line(line.id(), null, null, null)), order)).doesNotThrowAnyException();
        assertThatThrownBy(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.CONFIRMED,
                new StockReplyCmd.Line(OrderItemId.of(UUID.randomUUID()), null, null, null)), order))
                .isInstanceOf(InvalidStockReplyException.class);
        assertThatThrownBy(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RELEASED,
                new StockReplyCmd.Line(line.id(), null, null, null),
                new StockReplyCmd.Line(line.id(), null, null, null)), order))
                .isInstanceOf(InvalidStockReplyException.class);
        assertThatThrownBy(() -> StockSagaTransactions.checkLines(reply(StockReplyCmd.Type.RESERVATION_REJECTED,
                new StockReplyCmd.Line(line.id(), "OTHER", null, "INSUFFICIENT_STOCK")), order))
                .isInstanceOf(InvalidStockReplyException.class);
    }
}
