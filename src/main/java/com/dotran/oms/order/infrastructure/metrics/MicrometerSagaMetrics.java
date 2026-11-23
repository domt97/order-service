package com.dotran.oms.order.infrastructure.metrics;

import com.dotran.oms.order.application.metrics.SagaMetrics;
import com.dotran.oms.order.domain.enums.RejectionCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link SagaMetrics} on Micrometer. The oldest-PENDING age is a value cached by the reserve-timeout job (a metrics read
 * never runs SQL); 0 when nothing is PENDING or before the first run. Tags are bounded: never an id or an attempt number.
 */
@Component
public class MicrometerSagaMetrics implements SagaMetrics {

    static final String PREFIX = "order.saga.";
    /** The jobs whose gauges exist from startup (value 0). */
    static final String[] JOBS = {"reserve-timeout", "stock-resend"};

    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicReference<Instant> oldestPending = new AtomicReference<>();
    private final Map<String, AtomicInteger> consecutiveAborts = new ConcurrentHashMap<>();

    public MicrometerSagaMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        Gauge.builder(PREFIX + "oldest.pending.age.seconds", this, MicrometerSagaMetrics::oldestPendingAgeSeconds)
                .description("Age of the oldest PENDING checkout (by created_at)").register(registry);
        for (String job : JOBS) {
            aborts(job);
        }
    }

    double oldestPendingAgeSeconds() {
        Instant created = oldestPending.get();
        return created == null ? 0 : Math.max(0, Duration.between(created, clock.instant()).toMillis() / 1000.0);
    }

    private AtomicInteger aborts(String job) {
        return consecutiveAborts.computeIfAbsent(job, j -> {
            AtomicInteger value = new AtomicInteger();
            Gauge.builder(PREFIX + "job.consecutive.aborts", value, AtomicInteger::get).tag("job", j)
                    .description("Runs in a row that stopped early; reset by a clean run").register(registry);
            return value;
        });
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(PREFIX + name).tags(tags).register(registry);
    }

    @Override
    public void placed() {
        counter("checkout.placed").increment();
    }

    @Override
    public void rejected(RejectionCode code) {
        counter("checkout.rejected", "code", code.name()).increment();
    }

    @Override
    public void reserveTimeout() {
        counter("reserve.timeout").increment();
    }

    @Override
    public void stockCommandAttempt(String type, AttemptPath path) {
        counter("stock.command.attempts.over3", "type", type, "path", path.name()).increment();
    }

    @Override
    public void oldestPendingCreatedAt(Instant createdAt) {
        oldestPending.set(createdAt);
    }

    @Override
    public void jobAborted(String job) {
        counter("job.aborted", "job", job).increment();
        aborts(job).incrementAndGet();
    }

    @Override
    public void jobRunClean(String job) {
        aborts(job).set(0);
    }

    @Override
    public void jobFailure(String job, FailureKind kind) {
        counter("job.failure", "job", job, "kind", kind.name()).increment();
    }

    @Override
    public void counterGiveBackMissed() {
        counter("counter.giveback.missed").increment();
    }
}
