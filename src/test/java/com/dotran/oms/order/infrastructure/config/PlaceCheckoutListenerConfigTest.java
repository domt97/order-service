package com.dotran.oms.order.infrastructure.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlaceCheckoutListenerConfig")
class PlaceCheckoutListenerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PlaceCheckoutListenerConfig.class);

    @Test
    @DisplayName("application.yaml leaves the listener off: no PlaceCheckout.DLT topic bean, so no broker at startup")
    void offByDefault() {
        runner.run(context -> {
            assertThat(context.getEnvironment().getProperty("app.kafka.place-checkout.enabled")).isEqualTo("false");
            assertThat(context).doesNotHaveBean(NewTopic.class);
        });
    }

    @Test
    @DisplayName("with the listener on, the DLT topic is declared")
    void onDeclaresDlt() {
        runner.withPropertyValues("app.kafka.place-checkout.enabled=true").run(context ->
                assertThat(context.getBean(NewTopic.class).name()).isEqualTo("PlaceCheckout.DLT"));
    }
}
