package com.dotran.oms.order.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.stock} (application.yaml): the stock saga's timings (ADR 0007). {@code reserveTimeout}: how long a PENDING
 * checkout waits for every reserve reply (D3, much shorter than the 15-min hold). {@code resendInitial} /
 * {@code resendMax}: re-send interval of an open Confirm or Release, doubling to the max. The two jobs:
 * {@code startupGrace} (no reserve timeout before it has passed since start, so replies sent while the service was down
 * are read first), {@code runBudget} (checked before each call of a run), {@code batchSize} (checkouts per timeout run,
 * orders per re-send batch).
 */
@ConfigurationProperties("app.stock")
public record StockSagaProperties(Duration reserveTimeout, Duration resendInitial, Duration resendMax,
                                  Duration startupGrace, Duration runBudget, Integer batchSize) {

    public StockSagaProperties {
        reserveTimeout = reserveTimeout == null ? Duration.ofSeconds(150) : reserveTimeout;
        resendInitial = resendInitial == null ? Duration.ofSeconds(150) : resendInitial;
        resendMax = resendMax == null ? Duration.ofMinutes(10) : resendMax;
        startupGrace = startupGrace == null ? Duration.ofSeconds(150) : startupGrace;
        runBudget = runBudget == null ? Duration.ofSeconds(5) : runBudget;
        batchSize = batchSize == null ? 50 : batchSize;
        if (reserveTimeout.isNegative() || reserveTimeout.isZero()
                || reserveTimeout.compareTo(Duration.ofMinutes(15)) >= 0) {
            throw new IllegalArgumentException("app.stock.reserve-timeout must be > 0 and shorter than the 15-min hold");
        }
        if (resendInitial.isNegative() || resendInitial.isZero() || resendMax.compareTo(resendInitial) < 0) {
            throw new IllegalArgumentException("app.stock.resend-initial > 0 and resend-max >= resend-initial required");
        }
        if (startupGrace.isNegative() || runBudget.isNegative() || runBudget.isZero() || batchSize < 1
                || batchSize > 50) {
            throw new IllegalArgumentException("app.stock: startup-grace >= 0, run-budget > 0, batch-size 1..50");
        }
    }

    /** The defaults (tests). */
    public static StockSagaProperties defaults() {
        return new StockSagaProperties(null, null, null, null, null, null);
    }
}
