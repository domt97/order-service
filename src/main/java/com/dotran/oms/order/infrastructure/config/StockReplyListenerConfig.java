package com.dotran.oms.order.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The dead-letter topics of the five stock-reply listeners (one partition each, as PlaceCheckout.DLT). On unless
 * {@code app.kafka.stock-replies.enabled=false}, the same switch as the listeners.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.kafka.stock-replies.enabled", havingValue = "true", matchIfMissing = true)
public class StockReplyListenerConfig {

    @Bean
    public NewTopic stockReservedDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return dlt("StockReserved", replicas);
    }

    @Bean
    public NewTopic stockReservationRejectedDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return dlt("StockReservationRejected", replicas);
    }

    @Bean
    public NewTopic stockReservationConfirmedDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return dlt("StockReservationConfirmed", replicas);
    }

    @Bean
    public NewTopic stockReservationConfirmationFailedDeadLetterTopic(
            @Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return dlt("StockReservationConfirmationFailed", replicas);
    }

    @Bean
    public NewTopic stockReservationReleasedDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return dlt("StockReservationReleased", replicas);
    }

    private static NewTopic dlt(String topic, int replicas) {
        return TopicBuilder.name(topic + KafkaErrorHandlingConfig.DLT_SUFFIX).partitions(1).replicas(replicas).build();
    }
}
