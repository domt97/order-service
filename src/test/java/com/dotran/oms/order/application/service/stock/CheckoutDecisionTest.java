package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.EventId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.core.domain.id.OrderItemId;
import com.dotran.oms.core.domain.id.ProductId;
import com.dotran.oms.core.domain.id.StoreId;
import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.application.service.stock.CheckoutDecision.Verdict;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.enums.StockState;
import com.dotran.oms.order.domain.model.Order;
import com.dotran.oms.order.domain.model.OrderLine;
import com.dotran.oms.order.domain.valueobject.DeliverySnapshot;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutDecisionTest {

    static final UUID STORE = UUID.fromString("00000000-0000-0000-0000-00000000b001");

    static Order order(StockState state, List<RejectionReason> rejection) {
        Order o = Order.pending(CheckoutId.newId(), CustomerId.of(UUID.randomUUID()), MerchantId.of(UUID.randomUUID()),
                StoreId.of(STORE), "HCM01", LocalDate.of(2026, 11, 21), "VND", UUID.randomUUID(),
                new DeliverySnapshot("n", "p", "l", null, null, null, "c", null, "VN"),
                List.of(OrderLine.snapshot(UUID.randomUUID(), ProductId.of(UUID.randomUUID()), "TS-M", "Tee", null, 1,
                        BigDecimal.ONE, "VND", 0)), Instant.EPOCH);
        return new Order(o.id(), o.checkoutId(), o.customerId(), o.merchantId(), o.storeId(), o.storeCode(),
                o.localDate(), null, null, o.status(), state, rejection, null,
                state.requiresDueTime() ? Instant.EPOCH : null, 0, null, null, o.currency(), o.itemsTotal(),
                o.deliveryQuoteId(), null, null, o.delivery(), o.lines(), null, null, o.createdAt(), o.updatedAt());
    }

    private static StockReplyCmd rejected(String orderReason, StockReplyCmd.Line... lines) {
        return new StockReplyCmd(StockReplyCmd.Type.RESERVATION_REJECTED, EventId.of(UUID.randomUUID()), null,
                MerchantId.of(UUID.randomUUID()), StoreId.of(STORE), OrderId.of(UUID.randomUUID()), null, orderReason,
                null, List.of(lines));
    }

    @Test
    @DisplayName("D3: wait while any reserve is unanswered; then all RESERVED → finalize, anything else → reject")
    void verdicts() {
        Order reserved = order(StockState.RESERVED, null);
        Order waiting = order(StockState.RESERVE_SENT, null);
        Order refused = order(StockState.RESERVE_REJECTED, null);
        assertThat(CheckoutDecision.decide(List.of(reserved, waiting), false)).isEqualTo(Verdict.WAIT);
        assertThat(CheckoutDecision.decide(List.of(refused, waiting), false)).isEqualTo(Verdict.WAIT);
        assertThat(CheckoutDecision.decide(List.of(reserved, reserved), false)).isEqualTo(Verdict.FINALIZE);
        assertThat(CheckoutDecision.decide(List.of(reserved, refused), false)).isEqualTo(Verdict.REJECT);
        assertThat(CheckoutDecision.decide(List.of(reserved, waiting), true)).isEqualTo(Verdict.REJECT);
        assertThat(CheckoutDecision.decide(List.of(reserved), true)).isEqualTo(Verdict.FINALIZE);
    }

    @Test
    @DisplayName("inventory's reasons: line codes pass through; STALE_COMMAND → RESERVE_TIMEOUT, ORDER_CLOSED → RESERVE_FAILED")
    void reasonMapping() {
        OrderItemId item = OrderItemId.of(UUID.randomUUID());
        assertThat(CheckoutDecision.reserveRejection(rejected(null,
                new StockReplyCmd.Line(item, "TS-M", null, "INSUFFICIENT_STOCK"),
                new StockReplyCmd.Line(item, "TS-L", null, "NOT_ORDERABLE"))))
                .containsExactly(RejectionReason.line(RejectionCode.NOT_ORDERABLE, STORE, "TS-L"),
                        RejectionReason.line(RejectionCode.INSUFFICIENT_STOCK, STORE, "TS-M")); // sorted by SKU
        assertThat(CheckoutDecision.reserveRejection(rejected("STALE_COMMAND")))
                .containsExactly(RejectionReason.store(RejectionCode.RESERVE_TIMEOUT, STORE));
        assertThat(CheckoutDecision.reserveRejection(rejected("ORDER_CLOSED")))
                .containsExactly(RejectionReason.store(RejectionCode.RESERVE_FAILED, STORE));
        assertThat(CheckoutDecision.reserveRejection(rejected(null)))
                .containsExactly(RejectionReason.store(RejectionCode.RESERVE_FAILED, STORE));
        assertThat(CheckoutDecision.reserveRejection(rejected(null,
                new StockReplyCmd.Line(item, "X", null, "SOMETHING_NEW"))))
                .containsExactly(RejectionReason.store(RejectionCode.RESERVE_FAILED, STORE));
    }

    @Test
    @DisplayName("checkout reasons: every rejected order's reasons and RESERVE_TIMEOUT for each unanswered one (D3)")
    void checkoutReasons() {
        List<RejectionReason> why = List.of(RejectionReason.line(RejectionCode.UNKNOWN_SKU, STORE, "TS-M"));
        assertThat(CheckoutDecision.checkoutReasons(List.of(order(StockState.RESERVED, null),
                order(StockState.RESERVE_REJECTED, why), order(StockState.RESERVE_SENT, null))))
                .containsExactly(RejectionReason.store(RejectionCode.RESERVE_TIMEOUT, STORE), why.get(0));
    }
}
