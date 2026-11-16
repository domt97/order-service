package com.dotran.oms.order.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Only when the PlaceCheckout listener is on: its dead-letter topic (with the flag off, no topic bean exists and
 * startup never contacts a broker) and a WARN that placement is not built yet.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.kafka.place-checkout.enabled", havingValue = "true")
@Slf4j
public class PlaceCheckoutListenerConfig {

    @Bean
    public NewTopic placeCheckoutDeadLetterTopic(@Value("${app.kafka.dlt-replicas:1}") int replicas) {
        return TopicBuilder.name("PlaceCheckout" + KafkaErrorHandlingConfig.DLT_SUFFIX).partitions(1).replicas(replicas)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnPlacementNotBuilt() {
        log.warn("PlaceCheckout listener is ON, but placement is not built yet: a checkout that passes every check gets"
                + " no reply. Use only to test rejections.");
    }
}
