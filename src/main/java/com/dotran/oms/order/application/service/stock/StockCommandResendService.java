package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.core.annotation.UseCase;
import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.application.event.ConfirmStockReservationCommand;
import com.dotran.oms.order.application.event.ReleaseStockReservationCommand;
import com.dotran.oms.order.application.metrics.SagaMetrics;
import com.dotran.oms.order.application.metrics.SagaMetrics.AttemptPath;
import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import com.dotran.oms.order.application.repository.OrderRepository.Rescheduled;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.OrderRef;
import com.dotran.oms.order.application.service.stock.StockSagaTransactions.Resent;
import com.dotran.oms.order.application.support.DbFailures;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase.RunResult;
import com.dotran.oms.order.application.usecase.stock.ResendStockCommandsUseCase;
import com.dotran.oms.order.domain.enums.StockState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The re-send job's run (ADR 0006 obligation 2). Not {@code @Transactional}: one
 * {@link StockSagaTransactions#resendDueBatch} call per batch, the run budget checked before each call. A failed batch
 * is retried row by row ({@code resendOne}, at least one even when the budget is spent) so only the failing order is
 * counted, skipped for the run and deferred; a connection failure or a failure that names no row stops the run. From
 * attempt 3 each re-send or defer logs an ERROR and counts {@code stockCommandAttempt}. Never gives up.
 */
@UseCase
@RequiredArgsConstructor
@Slf4j
public class StockCommandResendService implements ResendStockCommandsUseCase {

    static final String JOB = "stock-resend";
    static final int ERROR_FROM_ATTEMPT = 3;

    private final StockSagaTransactions transactions;
    private final StockSagaProperties properties;
    private final SagaMetrics metrics;
    private final Clock clock;

    @Override
    public RunResult run() {
        Instant deadline = clock.instant().plus(properties.runBudget());
        Set<UUID> excluded = new HashSet<>();
        while (clock.instant().isBefore(deadline)) {
            List<Resent> resent;
            try {
                resent = transactions.resendDueBatch(Set.copyOf(excluded));
            } catch (StockSagaFailure failure) {
                if (DbFailures.isConnectionFailure(failure) || failure.orderRefs().isEmpty()) {
                    return abort(failure);
                }
                if (!rowByRow(failure, deadline, excluded)) {
                    return RunResult.ABORTED;
                }
                continue;
            } catch (RuntimeException e) {
                return abort(e);
            }
            resent.forEach(r -> attempted(r.order(), r.stockState(), r.attempts(), AttemptPath.RESENT, null));
            if (resent.size() < properties.batchSize()) {
                break;
            }
        }
        metrics.jobRunClean(JOB);
        return RunResult.CLEAN;
    }

    /**
     * The failed batch again, one order per transaction; the first call runs even when the budget is spent, so a
     * batch that keeps failing still drains one order per run. False when the run must stop.
     */
    private boolean rowByRow(StockSagaFailure batch, Instant deadline, Set<UUID> excluded) {
        boolean rowFailure = false;
        boolean first = true;
        for (OrderRef ref : batch.orderRefs()) {
            if (!first && !clock.instant().isBefore(deadline)) {
                break;
            }
            first = false;
            try {
                Optional<Resent> resent = transactions.resendOne(ref);
                resent.ifPresent(r -> attempted(r.order(), r.stockState(), r.attempts(), AttemptPath.RESENT, null));
            } catch (StockSagaFailure failure) {
                if (DbFailures.isConnectionFailure(failure)) {
                    return abortRun(failure);
                }
                rowFailure = true;
                if (!rowFailed(ref, failure, excluded)) {
                    return false;
                }
            } catch (RuntimeException e) {
                return abortRun(e);
            }
        }
        if (!rowFailure) {
            // The batch failed but no single order did (or the budget ran out first): count it once, here.
            FailureKind kind = DbFailures.kind(batch);
            log.error("Stock command re-send batch failed ({}), retried row by row: orders={} cause={}", kind,
                    batch.orderRefs().size(), DbFailures.describe(batch.getCause()));
            metrics.jobFailure(JOB, kind);
        }
        return true;
    }

    /** Counts the failure, excludes the order and defers it; false when the defer found the database down. */
    private boolean rowFailed(OrderRef ref, StockSagaFailure failure, Set<UUID> excluded) {
        FailureKind kind = DbFailures.kind(failure);
        log.error("Stock command re-send failed ({}): orderId={} merchantId={} cause={}", kind, ref.id().getValue(),
                ref.merchantId().getValue(), DbFailures.describe(failure.getCause()));
        metrics.jobFailure(JOB, kind);
        excluded.add(ref.id().getValue());
        try {
            Optional<Rescheduled> next = transactions.deferStockCommand(ref);
            next.ifPresent(r -> attempted(ref, r.stockState(), r.stockAttempts(), AttemptPath.DEFERRED, kind));
        } catch (RuntimeException e) {
            if (DbFailures.isConnectionFailure(e)) {
                abortRun(e);
                return false;
            }
            // Not counted again: the row stays due, fails next run and is counted there.
            log.error("Deferring the stock command failed: orderId={} cause={}", ref.id().getValue(),
                    DbFailures.describe(e));
        }
        return true;
    }

    private void attempted(OrderRef ref, StockState state, int attempts, AttemptPath path, FailureKind kind) {
        if (attempts < ERROR_FROM_ATTEMPT) {
            return;
        }
        String type = state == StockState.CONFIRM_SENT ? ConfirmStockReservationCommand.TYPE
                : ReleaseStockReservationCommand.TYPE;
        if (path == AttemptPath.RESENT) {
            log.error("{} re-sent, no reply yet: orderId={} merchantId={} attempt={} path={}", type,
                    ref.id().getValue(), ref.merchantId().getValue(), attempts, path);
        } else {
            log.error("{} deferred after a failed re-send ({}): orderId={} merchantId={} attempt={} path={}", type,
                    kind, ref.id().getValue(), ref.merchantId().getValue(), attempts, path);
        }
        metrics.stockCommandAttempt(type, path);
    }

    private RunResult abort(RuntimeException e) {
        abortRun(e);
        return RunResult.ABORTED;
    }

    private boolean abortRun(RuntimeException e) {
        log.error("Stock command re-send run stopped: cause={}", DbFailures.describe(e));
        metrics.jobAborted(JOB);
        return false;
    }
}
