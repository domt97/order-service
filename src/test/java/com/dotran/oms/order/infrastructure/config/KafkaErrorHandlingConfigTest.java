package com.dotran.oms.order.infrastructure.config;

import com.dotran.oms.core.logging.MdcProperties;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.message.OutboxPayloadDecoder;
import com.dotran.oms.order.infrastructure.message.InvalidPlaceCheckoutMessageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

@DisplayName("KafkaErrorHandlingConfig")
class KafkaErrorHandlingConfigTest {

    private static final String PHONE = "0901234567";

    @Test
    @DisplayName("a parse error never quotes the record: not in the exception message, not in the DLT log text")
    void noPersonalDataInParseErrors() {
        OutboxPayloadDecoder decoder = new OutboxPayloadDecoder(MessagingConfig.withoutSourceInErrors(new ObjectMapper()));
        String broken = "{\"delivery\":{\"recipientName\":\"Nguyen Van A\",\"phone\":\"" + PHONE + "\"},\"stores\":[";
        Throwable error = catchThrowable(() -> decoder.decode(broken, Object.class));
        assertThat(error).isInstanceOf(JsonProcessingException.class);
        assertThat(error.getMessage()).doesNotContain(PHONE).doesNotContain("Nguyen");

        Throwable wrapped = new ListenerExecutionFailedException("listener failed", error);
        assertThat(KafkaErrorHandlingConfig.hasParseError(wrapped)).isTrue();
        assertThat(KafkaErrorHandlingConfig.describe(wrapped))
                .contains("JsonEOFException").contains("line").doesNotContain(PHONE).doesNotContain("Nguyen");
    }

    @Test
    @DisplayName("our own invalid-message exception names the field; other errors are not parse errors")
    void describeOwn() {
        assertThat(KafkaErrorHandlingConfig.describe(new InvalidPlaceCheckoutMessageException("cartId missing or not a UUID")))
                .isEqualTo("InvalidPlaceCheckoutMessageException: cartId missing or not a UUID");
        assertThat(KafkaErrorHandlingConfig.hasParseError(new IllegalStateException("x"))).isFalse();
    }

    @Test
    @DisplayName("contract errors are not retried; a connection failure is, without limit; a cancelled statement is not one")
    void classification() {
        var handler = KafkaErrorHandlingConfig.errorHandler(mock(ConsumerRecordRecoverer.class),
                KafkaErrorHandlingConfig.backOff(), new MdcPropagation(new MdcProperties()));
        assertThat(handler.removeClassification(JsonProcessingException.class)).isFalse();
        assertThat(handler.removeClassification(InvalidPlaceCheckoutMessageException.class)).isFalse();
        assertThat(handler.removeClassification(DataIntegrityViolationException.class)).isFalse();
        assertThat(KafkaErrorHandlingConfig.isConnectionFailure(new DataAccessResourceFailureException("down"))).isTrue();
        assertThat(KafkaErrorHandlingConfig.isConnectionFailure(new DataAccessResourceFailureException("cancelled",
                new SQLException("canceling statement", "57014")))).isFalse();
    }
}
