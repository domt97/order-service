package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.application.metrics.SagaMetrics;
import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.CheckoutRef;
import com.dotran.oms.order.application.support.DbFailures;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The reserve-timeout job's run (D3). Not {@code @Transactional}: one {@link StockSagaTransactions#expireOneDue} call
 * per checkout, up to {@code batchSize} per run, the run budget checked before each call. A failing checkout is
 * counted, skipped for the rest of the run and deferred 60 s; a connection failure or a failure that names no row
 * stops the run. Nothing is done during the startup grace, so replies that queued while the service was down are read
 * before their checkouts time out.
 */
@UseCase
@Slf4j
public class ReserveTimeoutService implements ExpireDueReservesUseCase {

    static final String JOB = "reserve-timeout";

    private final StockSagaTransactions transactions;
    private final StockSagaProperties properties;
    private final SagaMetrics metrics;
    private final Clock clock;
    private final Instant workFrom;

    public ReserveTimeoutService(StockSagaTransactions transactions, StockSagaProperties properties,
                                 SagaMetrics metrics, Clock clock) {
        this.transactions = transactions;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.workFrom = clock.instant().plus(properties.startupGrace());
    }

    @Override
    public RunResult run() {
        if (clock.instant().isBefore(workFrom)) {
            return RunResult.SKIPPED;
        }
        try {
            metrics.oldestPendingCreatedAt(transactions.oldestPendingCreatedAt().orElse(null));
        } catch (RuntimeException e) {
            return abort(e);
        }
        Instant deadline = clock.instant().plus(properties.runBudget());
        Set<UUID> excluded = new HashSet<>();
        for (int i = 0; i < properties.batchSize() && clock.instant().isBefore(deadline); i++) {
            try {
                if (!transactions.expireOneDue(Set.copyOf(excluded))) {
                    break;
                }
            } catch (StockSagaFailure failure) {
                if (DbFailures.isConnectionFailure(failure) || failure.checkoutRef() == null) {
                    return abort(failure);
                }
                if (!rowFailed(failure.checkoutRef(), failure, excluded)) {
                    return RunResult.ABORTED;
                }
            } catch (RuntimeException e) {
                return abort(e);
            }
        }
        metrics.jobRunClean(JOB);
        return RunResult.CLEAN;
    }

    /** Counts the failure, excludes the checkout and defers it; false when the defer found the database down. */
    private boolean rowFailed(CheckoutRef ref, StockSagaFailure failure, Set<UUID> excluded) {
        FailureKind kind = DbFailures.kind(failure);
        log.error("Reserve timeout failed ({}): checkoutId={} customerId={} cause={}", kind, ref.id().getValue(),
                ref.customerId().getValue(), DbFailures.describe(failure.getCause()));
        metrics.jobFailure(JOB, kind);
        excluded.add(ref.id().getValue());
        try {
            transactions.deferReserve(ref);
        } catch (RuntimeException e) {
            if (DbFailures.isConnectionFailure(e)) {
                abort(e);
                return false;
            }
            // Not counted again: the row stays due, fails next run and is counted there.
            log.error("Deferring the reserve timeout failed: checkoutId={} cause={}", ref.id().getValue(),
                    DbFailures.describe(e));
        }
        return true;
    }

    private RunResult abort(RuntimeException e) {
        log.error("Reserve-timeout run stopped: cause={}", DbFailures.describe(e));
        metrics.jobAborted(JOB);
        return RunResult.ABORTED;
    }
}
