package com.dotran.oms.order.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * JSON of the outbox payloads and of stored reasons, independent of the application's ObjectMapper: ISO-8601 instants;
 * nulls written unless a field says NON_NULL (CheckoutRejected leaves out an absent checkoutId, storeId and sku).
 */
@Component
public class OutboxPayloadSerializer {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
