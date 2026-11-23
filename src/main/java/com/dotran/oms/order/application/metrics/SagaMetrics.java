package com.dotran.oms.order.application.metrics;

import com.dotran.oms.order.domain.enums.RejectionCode;

import java.time.Instant;

/**
 * Outbound port for the stock saga's metrics, so the application layer does not depend on Micrometer. Alerts are
 * listed in the service CLAUDE.md (open question: no alert backend yet).
 */
public interface SagaMetrics {

    void placed();

    void rejected(RejectionCode code);

    void reserveTimeout();

    /** One open command reached attempt 3 or more; the caller filters and logs the attempt number. */
    void stockCommandAttempt(String type, AttemptPath path);

    /** Set by the reserve-timeout job at the start of each run; null when no checkout is PENDING. */
    void oldestPendingCreatedAt(Instant createdAt);

    /** A job run stopped early (database down, or a failure that names no row). */
    void jobAborted(String job);

    /** A job run ended without stopping early (row failures included); resets the consecutive-abort gauge. */
    void jobRunClean(String job);

    void jobFailure(String job, FailureKind kind);

    /** A daily slot could not be given back (already 0, or not this merchant's row). */
    void counterGiveBackMissed();

    enum AttemptPath {
        /** Re-sent, no reply yet. */
        RESENT,
        /** Pushed back after the re-send failed in order-service. */
        DEFERRED
    }

    enum FailureKind {
        /** Lock timeout, deadlock or statement timeout: may pass on the next run. */
        TRANSIENT,
        PERSISTENT
    }
}
