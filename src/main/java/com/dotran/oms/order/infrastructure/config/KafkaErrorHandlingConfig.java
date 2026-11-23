package com.dotran.oms.order.infrastructure.config;

import com.dotran.oms.core.logging.LogSanitizer;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.order.application.service.stock.InvalidStockReplyException;
import com.dotran.oms.order.application.support.DbFailures;
import com.dotran.oms.order.infrastructure.message.InvalidPlaceCheckoutMessageException;
import com.dotran.oms.order.infrastructure.message.KafkaMdc;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.ArrayList;
import java.util.List;

/**
 * Error handling for every Kafka listener (as inventory-service). Contract errors go to {@code <topic>.DLT} at once;
 * database connection failures are retried without limit (backoff up to 1 min); other failures 6 times, then the DLT.
 * The DLT log line never quotes the record: a parse error is logged as its exception classes and position only (no
 * message, no stack trace), because PlaceCheckout carries personal data (BR-ORD-15).
 */
@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    static final String DLT_SUFFIX = ".DLT";
    static final int MAX_RETRIES = 6;
    static final long CONNECTION_FAILURE_MAX_INTERVAL_MS = 60_000L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<?, ?> kafkaTemplate, MdcPropagation mdcPropagation) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                // partition -1: let Kafka pick one, the DLT may have fewer partitions than the source topic
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));
        return errorHandler(deadLetter, backOff(), mdcPropagation);
    }

    static DefaultErrorHandler errorHandler(ConsumerRecordRecoverer deadLetter, ExponentialBackOffWithMaxRetries backOff,
                                            MdcPropagation mdcPropagation) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
                String key = record.key() == null ? null : LogSanitizer.escape(String.valueOf(record.key()));
                if (hasParseError(exception)) {
                    log.error("Sending {} record key={} partition={} offset={} to {}{}: {}", record.topic(), key,
                            record.partition(), record.offset(), record.topic(), DLT_SUFFIX, describe(exception));
                } else {
                    log.error("Sending {} record key={} partition={} offset={} to {}{}: {}", record.topic(), key,
                            record.partition(), record.offset(), record.topic(), DLT_SUFFIX, describe(exception),
                            exception);
                }
                deadLetter.accept(record, exception);
            }
        };
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setBackOffFunction((record, exception) -> isConnectionFailure(exception) ? connectionFailureBackOff() : null);
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                InvalidPlaceCheckoutMessageException.class,
                InvalidStockReplyException.class,
                DataIntegrityViolationException.class);
        return handler;
    }

    static boolean hasParseError(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JsonProcessingException) {
                return true;
            }
        }
        return false;
    }

    /** Exception classes of the chain; for a parse error its line and column; our own exceptions their message. */
    static String describe(Throwable exception) {
        List<String> parts = new ArrayList<>();
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String part = cause.getClass().getSimpleName();
            if (cause instanceof JsonProcessingException json) {
                JsonLocation at = json.getLocation();
                if (at != null) {
                    part += " at line " + at.getLineNr() + " column " + at.getColumnNr();
                }
            } else if (cause instanceof InvalidPlaceCheckoutMessageException
                    || cause instanceof InvalidStockReplyException) {
                // Names the field only, never its value.
                part += ": " + cause.getMessage();
            }
            parts.add(part);
        }
        return String.join(" <- ", parts);
    }

    /** Delegates to {@link DbFailures#isConnectionFailure} (shared with the saga jobs). */
    static boolean isConnectionFailure(Throwable exception) {
        return DbFailures.isConnectionFailure(exception);
    }

    static ExponentialBackOff connectionFailureBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(CONNECTION_FAILURE_MAX_INTERVAL_MS);
        return backOff;
    }

    static ExponentialBackOffWithMaxRetries backOff() {
        // 1 + 2 + 4 + 8 + 10 + 10 s = 35 s between attempts (see the poll sizing in application.yaml)
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        return backOff;
    }
}
