package com.dotran.oms.order.application.service.stock;

import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.support.MutableClock;
import com.dotran.oms.order.application.metrics.SagaMetrics;
import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import com.dotran.oms.order.application.service.stock.StockSagaFailure.CheckoutRef;
import com.dotran.oms.order.application.usecase.stock.ExpireDueReservesUseCase.RunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ReserveTimeoutService")
class ReserveTimeoutServiceTest {

    private static final String JOB = "reserve-timeout";

    private final StockSagaTransactions tx = mock(StockSagaTransactions.class);
    private final SagaMetrics metrics = mock(SagaMetrics.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-22T03:00:00Z"));
    private ReserveTimeoutService service;

    @BeforeEach
    void setUp() {
        service = new ReserveTimeoutService(tx, StockSagaProperties.defaults(), metrics, clock);
        when(tx.oldestPendingCreatedAt()).thenReturn(Optional.empty());
    }

    private void afterGrace() {
        clock.advance(Duration.ofSeconds(150));
    }

    @SuppressWarnings("unchecked")
    private List<Set<UUID>> excludedPerCall(int calls) {
        ArgumentCaptor<Set<UUID>> sets = ArgumentCaptor.forClass(Set.class);
        verify(tx, times(calls)).expireOneDue(sets.capture());
        return sets.getAllValues();
    }

    @Test
    @DisplayName("startup grace: nothing read or counted before start + 150 s; work from then on")
    void startupGrace() {
        clock.advance(Duration.ofSeconds(149));
        assertThat(service.run()).isEqualTo(RunResult.SKIPPED);
        verifyNoInteractions(tx, metrics);

        clock.advance(Duration.ofSeconds(1));
        when(tx.expireOneDue(any())).thenReturn(true, false);
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(tx, times(2)).expireOneDue(any());
        verify(metrics).jobRunClean(JOB);
    }

    @Test
    @DisplayName("the gauge gets the oldest PENDING created_at, null when none")
    void gauge() {
        afterGrace();
        Instant oldest = Instant.parse("2026-11-22T02:50:00Z");
        when(tx.oldestPendingCreatedAt()).thenReturn(Optional.of(oldest), Optional.empty());
        when(tx.expireOneDue(any())).thenReturn(false);
        service.run();
        service.run();
        verify(metrics).oldestPendingCreatedAt(oldest);
        verify(metrics).oldestPendingCreatedAt(null);
    }

    @Test
    @DisplayName("the first checkout fails: counted PERSISTENT, excluded, deferred; the next due one is still decided")
    void poisonedRow() {
        afterGrace();
        CheckoutRef bad = Failures.checkout();
        when(tx.expireOneDue(any())).thenThrow(Failures.on(bad, Failures.persistent())).thenReturn(true, false);

        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        List<Set<UUID>> excluded = excludedPerCall(3);
        assertThat(excluded.get(0)).isEmpty();
        assertThat(excluded.get(1)).containsExactly(bad.id().getValue());
        assertThat(excluded.get(2)).containsExactly(bad.id().getValue());
        verify(metrics).jobFailure(JOB, FailureKind.PERSISTENT);
        verify(tx).deferReserve(bad);
        verify(metrics).jobRunClean(JOB);
        verify(metrics, never()).jobAborted(anyString());
    }

    @Test
    @DisplayName("a lock timeout on a row is TRANSIENT")
    void transientKind() {
        afterGrace();
        when(tx.expireOneDue(any())).thenThrow(Failures.on(Failures.checkout(), Failures.lockTimeout()))
                .thenReturn(false);
        service.run();
        verify(metrics).jobFailure(JOB, FailureKind.TRANSIENT);
    }

    @Test
    @DisplayName("connection failure on a row: run stops, jobAborted, no defer, not counted as a row failure")
    void connectionFailure() {
        afterGrace();
        when(tx.expireOneDue(any())).thenThrow(Failures.on(Failures.checkout(), Failures.connection()));
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(tx, times(1)).expireOneDue(any());
        verify(tx, never()).deferReserve(any());
        verify(metrics).jobAborted(JOB);
        verify(metrics, never()).jobFailure(anyString(), any());
        verify(metrics, never()).jobRunClean(anyString());
    }

    @Test
    @DisplayName("a failure that names no row (scan, commit) stops the run with jobAborted")
    void failureWithoutId() {
        afterGrace();
        when(tx.expireOneDue(any())).thenThrow(new IllegalStateException("commit failed"));
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(tx, times(1)).expireOneDue(any());
        verify(metrics).jobAborted(JOB);
        verify(tx, never()).deferReserve(any());
    }

    @Test
    @DisplayName("the gauge read fails: run stops before any checkout, jobAborted")
    void gaugeReadFails() {
        afterGrace();
        when(tx.oldestPendingCreatedAt()).thenThrow(Failures.connection());
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(tx, never()).expireOneDue(any());
        verify(metrics).jobAborted(JOB);
    }

    @Test
    @DisplayName("defer fails: ERROR only, jobFailure once in total, the run goes on")
    void deferFails() {
        afterGrace();
        CheckoutRef bad = Failures.checkout();
        when(tx.expireOneDue(any())).thenThrow(Failures.on(bad, Failures.persistent())).thenReturn(false);
        when(tx.deferReserve(bad)).thenThrow(Failures.lockTimeout());
        assertThat(service.run()).isEqualTo(RunResult.CLEAN);
        verify(metrics, times(1)).jobFailure(anyString(), any());
        verify(tx, times(2)).expireOneDue(any());
    }

    @Test
    @DisplayName("defer hits a connection failure: run stops, jobAborted")
    void deferConnectionFailure() {
        afterGrace();
        CheckoutRef bad = Failures.checkout();
        when(tx.expireOneDue(any())).thenThrow(Failures.on(bad, Failures.persistent()));
        when(tx.deferReserve(bad)).thenThrow(Failures.connection());
        assertThat(service.run()).isEqualTo(RunResult.ABORTED);
        verify(tx, times(1)).expireOneDue(any());
        verify(metrics, times(1)).jobFailure(JOB, FailureKind.PERSISTENT);
        verify(metrics).jobAborted(JOB);
        verify(metrics, never()).jobRunClean(anyString());
    }

    @Test
    @DisplayName("the 5 s budget is checked before each call; at most 50 checkouts per run")
    void bounded() {
        afterGrace();
        when(tx.expireOneDue(any())).thenAnswer(i -> {
            clock.advance(Duration.ofSeconds(2));
            return true;
        });
        service.run();
        verify(tx, times(3)).expireOneDue(any());

        when(tx.expireOneDue(any())).thenReturn(true);
        org.mockito.Mockito.clearInvocations(tx);
        service.run();
        verify(tx, times(50)).expireOneDue(any());
    }
}
