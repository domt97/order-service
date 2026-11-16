package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.annotation.PersistenceAdapter;
import com.dotran.oms.order.application.repository.TransactionTimeoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/** {@code set_config(..., true)} is the function form of {@code SET LOCAL}: it ends with the transaction. */
@PersistenceAdapter
@RequiredArgsConstructor
public class TransactionTimeoutPersistenceAdapter implements TransactionTimeoutRepository {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyLocal(Duration lockTimeout, Duration statementTimeout) {
        jdbc.queryForList("SELECT set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)",
                lockTimeout.toMillis() + "ms", statementTimeout.toMillis() + "ms");
    }
}
