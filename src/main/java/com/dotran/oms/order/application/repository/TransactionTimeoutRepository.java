package com.dotran.oms.order.application.repository;

import java.time.Duration;

/** Sets {@code lock_timeout} and {@code statement_timeout} for the rest of the current transaction (SET LOCAL). */
public interface TransactionTimeoutRepository {

    void applyLocal(Duration lockTimeout, Duration statementTimeout);
}
