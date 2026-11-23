package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction boundaries the poll sizing and SKIP LOCKED depend on: the services make one transactional call each and
 * are never transactional themselves; the saga transactions are bounded.
 */
class TransactionalAnnotationTest {

    @Test
    @DisplayName("StockReplyService is not @Transactional; StockSagaTransactions.apply has timeout 10 s")
    void boundaries() throws Exception {
        assertThat(StockReplyService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(Arrays.stream(StockReplyService.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(Transactional.class))).allMatch(a -> a == null);
        Method apply = StockSagaTransactions.class.getMethod("apply", StockReplyCmd.class);
        assertThat(apply.getAnnotation(Transactional.class).timeout()).isEqualTo(10);
    }

    @Test
    @DisplayName("the job services are not @Transactional; expire/resend 10 s, defer writes and the gauge read 5 s")
    void jobBoundaries() throws Exception {
        for (Class<?> service : new Class<?>[]{ReserveTimeoutService.class, StockCommandResendService.class}) {
            assertThat(service.getAnnotation(Transactional.class)).as(service.getSimpleName()).isNull();
            assertThat(Arrays.stream(service.getDeclaredMethods()).map(m -> m.getAnnotation(Transactional.class)))
                    .as(service.getSimpleName()).allMatch(a -> a == null);
        }
        Class<?> t = StockSagaTransactions.class;
        assertThat(t.getMethod("expireOneDue", Set.class).getAnnotation(Transactional.class).timeout()).isEqualTo(10);
        assertThat(t.getMethod("resendDueBatch", Set.class).getAnnotation(Transactional.class).timeout())
                .isEqualTo(10);
        assertThat(t.getMethod("resendOne", StockSagaFailure.OrderRef.class).getAnnotation(Transactional.class)
                .timeout()).isEqualTo(10);
        assertThat(t.getMethod("deferReserve", StockSagaFailure.CheckoutRef.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(5);
        assertThat(t.getMethod("deferStockCommand", StockSagaFailure.OrderRef.class)
                .getAnnotation(Transactional.class).timeout()).isEqualTo(5);
        Transactional gauge = t.getMethod("oldestPendingCreatedAt").getAnnotation(Transactional.class);
        assertThat(gauge.timeout()).isEqualTo(5);
        assertThat(gauge.readOnly()).isTrue();
    }
}
