package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.support.MutableClock;
import com.dotran.oms.order.application.metrics.SagaMetrics;
import com.dotran.oms.order.application.metrics.SagaMetrics.AttemptPath;
import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import com.dotran.oms.order.application.repository.OrderRepository.Rescheduled;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.OrderRef;
import com.dotran.oms.order.application.service.stock.StockSagaTransactions.Resent;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase.RunResult;
import com.dotran.oms.order.domain.enums.StockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StockCommandResendService")
class StockCommandResendServiceTest {

    private static final String JOB = "stock-resend";

    private final StockSagaTransactions tx = mock(StockSagaTransactions.class);
    private final SagaMetrics metrics = mock(SagaMetrics.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T03:00:00Z"));
    private final StockCommandResendService service = new StockCommandResendService(tx,
            StockSagaProperties.defaults(), metrics, clock);

    private static Resent resent(OrderRef ref, int attempts) {
        return new Resent(ref, StockState.CONFIRM_SENT, attempts);
    }

    private static List<OrderRef> refs(int n) {
        return IntStream.range(0, n).mapToObj(i -> Failures.order()).toList();
    }

    @Test
    @DisplayName("nothing due: one batch call, clean run")
    void nothingDue() {
        when(tx.resendDueBatch(any())).thenReturn(List.of());
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(tx, times(1)).resendDueBatch(any());
        verify(metrics).jobRunClean(JOB);
    }

    @Test
    @DisplayName("attempt 3 and later: stockCommandAttempt(type, RESENT); attempts 1-2 not counted")
    void attemptThree() {
        OrderRef a = Failures.order();
        OrderRef b = Failures.order();
        when(tx.resendDueBatch(any())).thenReturn(List.of(resent(a, 2),
                new Resent(b, StockState.RELEASE_SENT, 3)));
        service.run();
        verify(metrics).stockCommandAttempt("ReleaseStockReservation", AttemptPath.RESENT);
        verify(metrics, times(1)).stockCommandAttempt(anyString(), any());
    }

    @Test
    @DisplayName("a batch with one poisoned order: the other 49 re-sent row by row, the bad one counted once and deferred")
    void poisonedInBatch() {
        List<OrderRef> batch = refs(50);
        OrderRef bad = batch.get(7);
        when(tx.resendDueBatch(any())).thenThrow(Failures.on(batch, Failures.persistent())).thenReturn(List.of());
        for (OrderRef r : batch) {
            if (r == bad) {
                when(tx.resendOne(r)).thenThrow(Failures.on(List.of(r), Failures.persistent()));
            } else {
                when(tx.resendOne(r)).thenReturn(Optional.of(resent(r, 1)));
            }
        }
        when(tx.deferStockCommand(bad)).thenReturn(Optional.of(new Rescheduled(1, StockState.CONFIRM_SENT)));

        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(tx, times(50)).resendOne(any());
        verify(tx, times(1)).deferStockCommand(any());
        verify(tx).deferStockCommand(bad);
        verify(metrics, times(1)).jobFailure(JOB, FailureKind.PERSISTENT);
        verify(metrics).jobRunClean(JOB);
        @SuppressWarnings("unchecked")
        var sets = org.mockito.ArgumentCaptor.forClass((Class<java.util.Set<java.util.UUID>>) (Class<?>) java.util.Set.class);
        verify(tx, times(2)).resendDueBatch(sets.capture());
        assertThat(sets.getAllValues().get(1)).containsExactly(bad.id().getValue());
    }

    @Test
    @DisplayName("three deferrals: ERROR + stockCommandAttempt(type, DEFERRED) at the third")
    void deferredThird() {
        OrderRef bad = Failures.order();
        // As the real scan: the failing order is due until the run excludes it.
        when(tx.resendDueBatch(any())).thenAnswer(i -> {
            if (((java.util.Set<?>) i.getArgument(0)).isEmpty()) {
                throw Failures.on(List.of(bad), Failures.persistent());
            }
            return List.of();
        });
        when(tx.resendOne(bad)).thenThrow(Failures.on(List.of(bad), Failures.persistent()));
        when(tx.deferStockCommand(bad)).thenReturn(Optional.of(new Rescheduled(1, StockState.CONFIRM_SENT)),
                Optional.of(new Rescheduled(2, StockState.CONFIRM_SENT)),
                Optional.of(new Rescheduled(3, StockState.CONFIRM_SENT)));
        service.run();
        service.run();
        verify(metrics, never()).stockCommandAttempt(anyString(), any());
        service.run();
        verify(metrics).stockCommandAttempt("ConfirmStockReservation", AttemptPath.DEFERRED);
        verify(metrics, times(3)).jobFailure(JOB, FailureKind.PERSISTENT);
        verify(tx, never()).resendDueBatch(org.mockito.ArgumentMatchers.argThat(set -> set.size() > 1));
    }

    @Test
    @DisplayName("a batch failure whose row retry succeeds on every row: jobFailure once, with the batch's kind")
    void batchFailureAllRowsFine() {
        List<OrderRef> batch = refs(3);
        when(tx.resendDueBatch(any())).thenThrow(Failures.on(batch, Failures.lockTimeout())).thenReturn(List.of());
        batch.forEach(r -> when(tx.resendOne(r)).thenReturn(Optional.of(resent(r, 1))));
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(metrics, times(1)).jobFailure(JOB, FailureKind.TRANSIENT);
        verify(tx, never()).deferStockCommand(any());
        verify(metrics).jobRunClean(JOB);
    }

    @Test
    @DisplayName("budget spent at the batch failure: exactly one resendOne; success → jobFailure once, no defer")
    void budgetSpentForcedOneSucceeds() {
        List<OrderRef> batch = refs(3);
        when(tx.resendDueBatch(any())).thenAnswer(i -> {
            clock.advance(Duration.ofSeconds(6));
            throw Failures.on(batch, Failures.persistent());
        });
        when(tx.resendOne(any())).thenAnswer(i -> Optional.of(resent(i.getArgument(0), 1)));
        service.run();
        verify(tx, times(1)).resendOne(batch.get(0));
        verify(tx, times(1)).resendOne(any());
        verify(metrics, times(1)).jobFailure(JOB, FailureKind.PERSISTENT);
        verify(tx, never()).deferStockCommand(any());
    }

    @Test
    @DisplayName("budget spent at the batch failure, forced resendOne fails: jobFailure once (the row's), one defer")
    void budgetSpentForcedOneFails() {
        List<OrderRef> batch = refs(3);
        when(tx.resendDueBatch(any())).thenAnswer(i -> {
            clock.advance(Duration.ofSeconds(6));
            throw Failures.on(batch, Failures.persistent());
        });
        when(tx.resendOne(batch.get(0))).thenThrow(Failures.on(List.of(batch.get(0)), Failures.lockTimeout()));
        service.run();
        verify(tx, times(1)).resendOne(any());
        verify(metrics, times(1)).jobFailure(anyString(), any());
        verify(metrics).jobFailure(JOB, FailureKind.TRANSIENT);
        verify(tx, times(1)).deferStockCommand(batch.get(0));
    }

    @Test
    @DisplayName("budget runs out partway, no row failed: jobFailure once, the rest left for the next run")
    void budgetRunsOutPartway() {
        List<OrderRef> batch = refs(5);
        when(tx.resendDueBatch(any())).thenThrow(Failures.on(batch, Failures.persistent()));
        when(tx.resendOne(any())).thenAnswer(i -> {
            clock.advance(Duration.ofSeconds(2));
            return Optional.of(resent(i.getArgument(0), 1));
        });
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(tx, times(3)).resendOne(any());
        verify(metrics, times(1)).jobFailure(JOB, FailureKind.PERSISTENT);
        verify(tx, times(1)).resendDueBatch(any());
    }

    @Test
    @DisplayName("failure with no id: run stops, jobAborted once, no row retry or defer")
    void failureWithoutId() {
        when(tx.resendDueBatch(any())).thenThrow(new IllegalStateException("scan failed"));
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(metrics, times(1)).jobAborted(JOB);
        verify(tx, never()).resendOne(any());
        verify(tx, never()).deferStockCommand(any());
        verify(metrics, never()).jobRunClean(anyString());
    }

    @Test
    @DisplayName("connection failure on the batch: run stops, no row retry")
    void batchConnectionFailure() {
        when(tx.resendDueBatch(any())).thenThrow(Failures.on(refs(2), Failures.connection()));
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(tx, never()).resendOne(any());
        verify(metrics).jobAborted(JOB);
        verify(metrics, never()).jobFailure(anyString(), any());
    }

    @Test
    @DisplayName("defer throws a connection failure mid-batch: run stops, jobAborted once, no further resendOne")
    void deferConnectionFailure() {
        List<OrderRef> batch = refs(3);
        when(tx.resendDueBatch(any())).thenThrow(Failures.on(batch, Failures.persistent()));
        when(tx.resendOne(batch.get(0))).thenThrow(Failures.on(List.of(batch.get(0)), Failures.persistent()));
        when(tx.deferStockCommand(batch.get(0))).thenThrow(Failures.connection());
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(tx, times(1)).resendOne(any());
        verify(metrics, times(1)).jobAborted(JOB);
        verify(metrics, times(1)).jobFailure(anyString(), any());
    }

    @Test
    @DisplayName("defer throws 55P03: the run goes on, no attempt-3 log or metric for that row")
    void deferLockTimeout() {
        List<OrderRef> batch = refs(2);
        when(tx.resendDueBatch(any())).thenThrow(Failures.on(batch, Failures.persistent())).thenReturn(List.of());
        when(tx.resendOne(batch.get(0))).thenThrow(Failures.on(List.of(batch.get(0)), Failures.persistent()));
        when(tx.resendOne(batch.get(1))).thenReturn(Optional.of(resent(batch.get(1), 1)));
        when(tx.deferStockCommand(batch.get(0))).thenThrow(Failures.lockTimeout());
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(tx, times(2)).resendOne(any());
        verify(metrics, never()).stockCommandAttempt(anyString(), any());
        verify(metrics, times(1)).jobFailure(anyString(), any());
    }

    @Test
    @DisplayName("a full batch is followed by the next batch; a short one ends the run")
    void fullBatchContinues() {
        List<Resent> full = refs(50).stream().map(r -> resent(r, 1)).toList();
        when(tx.resendDueBatch(any())).thenReturn(full, List.of(resent(Failures.order(), 1)));
        service.run();
        verify(tx, times(2)).resendDueBatch(any());
    }
}
