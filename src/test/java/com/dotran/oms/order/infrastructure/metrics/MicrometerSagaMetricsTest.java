package com.dotran.oms.order.infrastructure.metrics;

import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import com.dotran.oms.order.support.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerSagaMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T03:00:00Z"));
    private final MicrometerSagaMetrics metrics = new MicrometerSagaMetrics(registry, clock);

    private double gauge(String name, String... tags) {
        return registry.get(MicrometerSagaMetrics.PREFIX + name).tags(tags).gauge().value();
    }

    @Test
    @DisplayName("oldest-PENDING age: 0 before the first run and with nothing PENDING; else seconds since created_at")
    void oldestPending() {
        assertThat(gauge("oldest.pending.age.seconds")).isZero();
        metrics.oldestPendingCreatedAt(clock.instant().minusSeconds(400));
        assertThat(gauge("oldest.pending.age.seconds")).isEqualTo(400.0);
        metrics.oldestPendingCreatedAt(null);
        assertThat(gauge("oldest.pending.age.seconds")).isZero();
    }

    @Test
    @DisplayName("consecutive aborts: 0 at startup for both jobs, +1 per aborted run, reset by a clean run")
    void consecutiveAborts() {
        assertThat(gauge("job.consecutive.aborts", "job", "reserve-timeout")).isZero();
        assertThat(gauge("job.consecutive.aborts", "job", "stock-resend")).isZero();
        metrics.jobAborted("stock-resend");
        metrics.jobAborted("stock-resend");
        assertThat(gauge("job.consecutive.aborts", "job", "stock-resend")).isEqualTo(2.0);
        metrics.jobFailure("stock-resend", FailureKind.TRANSIENT);
        metrics.jobRunClean("stock-resend");
        assertThat(gauge("job.consecutive.aborts", "job", "stock-resend")).isZero();
        assertThat(registry.get(MicrometerSagaMetrics.PREFIX + "job.failure").tags("kind", "TRANSIENT").counter()
                .count()).isEqualTo(1.0);
    }
}
