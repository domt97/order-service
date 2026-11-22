package com.dotran.oms.order.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StockReplyListenerConfig")
class StockReplyListenerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(StockReplyListenerConfig.class);

    @Test
    @DisplayName("on by default (a started saga must always end): the five reply DLTs are declared")
    void onByDefault() {
        runner.run(context -> assertThat(context.getBeansOfType(NewTopic.class).values().stream()
                .map(NewTopic::name)).containsExactlyInAnyOrder("StockReserved.DLT", "StockReservationRejected.DLT",
                "StockReservationConfirmed.DLT", "StockReservationConfirmationFailed.DLT",
                "StockReservationReleased.DLT"));
    }

    @Test
    @DisplayName("app.kafka.stock-replies.enabled=false: no DLT topics (a local run without Kafka)")
    void off() {
        runner.withPropertyValues("app.kafka.stock-replies.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(NewTopic.class));
    }
}
