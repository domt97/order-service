package com.dotran.oms.order.application.support;

import com.dotran.oms.order.application.metrics.SagaMetrics.FailureKind;
import lombok.experimental.UtilityClass;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** How a database failure is classified, shared by the Kafka error handler and the saga jobs. */
@UtilityClass
public class DbFailures {

    private static final String QUERY_CANCELED = "57014";
    /** Lock timeout, deadlock, statement timeout: may pass on the next try. */
    private static final Set<String> TRANSIENT_STATES = Set.of("55P03", "40P01", QUERY_CANCELED);

    /**
     * The database cannot be reached (also when a transaction cannot begin). As inventory-service: a cancelled
     * statement (57014) is a timeout, not a connection failure.
     */
    public static boolean isConnectionFailure(Throwable exception) {
        boolean connectionFailure = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                return false;
            }
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotCreateTransactionException) {
                connectionFailure = true;
            }
        }
        return connectionFailure;
    }

    /** TRANSIENT for a lock timeout, deadlock or statement/transaction timeout anywhere in the chain. */
    public static FailureKind kind(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransactionTimedOutException
                    || cause instanceof SQLException sql && TRANSIENT_STATES.contains(sql.getSQLState())) {
                return FailureKind.TRANSIENT;
            }
        }
        return FailureKind.PERSISTENT;
    }

    /**
     * The cause chain as class names and SQL states only, for a log line: a message or server detail can carry row
     * values (delivery PII, BR-ORD-15).
     */
    public static String describe(Throwable exception) {
        List<String> parts = new ArrayList<>();
        for (Throwable cause = exception; cause != null && parts.size() < 10; cause = cause.getCause()) {
            String part = cause.getClass().getSimpleName();
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                part += "(" + sql.getSQLState() + ")";
            }
            parts.add(part);
        }
        return String.join(" <- ", parts);
    }
}
