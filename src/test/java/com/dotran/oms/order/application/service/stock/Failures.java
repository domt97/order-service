package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.domain.id.CustomerId;
import com.dotran.oms.core.domain.id.MerchantId;
import com.dotran.oms.core.domain.id.OrderId;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.CheckoutRef;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.OrderRef;
import com.dotran.oms.order.common.id.CheckoutId;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Failures as the jobs see them. */
final class Failures {

    private Failures() {
    }

    static CheckoutRef checkout() {
        return new CheckoutRef(CheckoutId.of(UUID.randomUUID()), CustomerId.of(UUID.randomUUID()));
    }

    static OrderRef order() {
        return new OrderRef(OrderId.of(UUID.randomUUID()), MerchantId.of(UUID.randomUUID()));
    }

    static RuntimeException connection() {
        return new DataAccessResourceFailureException("down");
    }

    static RuntimeException lockTimeout() {
        return new CannotAcquireLockException("lock", new SQLException("lock timeout", "55P03"));
    }

    static RuntimeException persistent() {
        return new DataIntegrityViolationException("check", new SQLException("check", "23514"));
    }

    static StockSagaFailure on(CheckoutRef ref, RuntimeException cause) {
        return StockSagaFailure.checkout(ref, cause);
    }

    static StockSagaFailure on(List<OrderRef> refs, RuntimeException cause) {
        return StockSagaFailure.orders(refs, cause);
    }
}
