package com.dotran.oms.order.application.support;

import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DbFailures")
class DbFailuresTest {

    @Test
    @DisplayName("connection failure: resource failure or no transaction; a cancelled statement is not one")
    void connection() {
        assertThat(DbFailures.isConnectionFailure(new DataAccessResourceFailureException("down"))).isTrue();
        assertThat(DbFailures.isConnectionFailure(new RuntimeException(new CannotCreateTransactionException("x"))))
                .isTrue();
        assertThat(DbFailures.isConnectionFailure(new DataAccessResourceFailureException("cancelled",
                new SQLException("cancel", "57014")))).isFalse();
        assertThat(DbFailures.isConnectionFailure(new IllegalStateException("x"))).isFalse();
    }

    @Test
    @DisplayName("kind: 55P03 / 40P01 / 57014 or a transaction timeout anywhere in the chain → TRANSIENT")
    void kind() {
        for (String state : new String[]{"55P03", "40P01", "57014"}) {
            assertThat(DbFailures.kind(new RuntimeException(new CannotAcquireLockException("x",
                    new SQLException("x", state))))).isEqualTo(FailureKind.TRANSIENT);
        }
        assertThat(DbFailures.kind(new TransactionTimedOutException("late"))).isEqualTo(FailureKind.TRANSIENT);
        assertThat(DbFailures.kind(new DataIntegrityViolationException("x", new SQLException("x", "23514"))))
                .isEqualTo(FailureKind.PERSISTENT);
        assertThat(DbFailures.kind(new IllegalStateException("x"))).isEqualTo(FailureKind.PERSISTENT);
    }

    @Test
    @DisplayName("describe: class names and SQL states only, never a message")
    void describe() {
        String text = DbFailures.describe(new DataIntegrityViolationException("Failing row contains (0901234567)",
                new SQLException("Detail: Nguyen Van A", "23502")));
        assertThat(text).isEqualTo("DataIntegrityViolationException <- SQLException(23502)");
        assertThat(text).doesNotContain("0901234567").doesNotContain("Nguyen");
    }
}
