package com.dotran.oms.order.application.usecase.stock;

/** One run of the reserve-timeout job (D3): decides PENDING checkouts whose reserve deadline has passed. */
public interface ExpireDueReservesUseCase {

    RunResult run();

    enum RunResult {
        /** Startup grace not over yet: nothing done, the abort count untouched. */
        SKIPPED,
        /** Ended without stopping early (row failures included). */
        CLEAN,
        /** Stopped early: database down or a failure that names no row. */
        ABORTED
    }
}
