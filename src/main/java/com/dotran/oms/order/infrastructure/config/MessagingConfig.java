package com.dotran.oms.order.infrastructure.config;

import com.dotran.oms.core.message.OutboxPayloadDecoder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    /**
     * oms-core's decoder of the Debezium-encoded outbox payloads (not a bean in oms-core). Its mapper never quotes the
     * record in an error message: PlaceCheckout holds a delivery address, name and phone (BR-ORD-15, never logged).
     */
    @Bean
    public OutboxPayloadDecoder outboxPayloadDecoder(ObjectMapper objectMapper) {
        return new OutboxPayloadDecoder(withoutSourceInErrors(objectMapper));
    }

    @SuppressWarnings("deprecation") // JsonParser.Feature works on every 2.x; StreamReadFeature needs a JsonFactory rebuild
    static ObjectMapper withoutSourceInErrors(ObjectMapper objectMapper) {
        ObjectMapper copy = objectMapper.copy();
        copy.configure(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION, false);
        return copy;
    }
}
