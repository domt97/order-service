package com.dotran.oms.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yaml includes the profile {@code core}, so oms-core's {@code application-core.yaml} supplies the settings
 * every OMS service shares. This test loads the configuration files the way Spring Boot does (no beans, no database) and
 * fails if the profile is no longer included or oms-core stops shipping the file: the AWS client settings below are
 * defined only there.
 */
class CoreProfileConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("the core profile is included and supplies the shared settings")
    void coreProfileSuppliesSharedSettings() {
        runner.run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getActiveProfiles()).contains("core");
            // only in oms-core's application-core.yaml
            assertThat(env.getProperty("springdoc.api-docs.path")).isEqualTo("/api-docs");
            assertThat(env.getProperty("aws.region")).isNotBlank();
            assertThat(env.getProperty("aws.endpoint")).isNotBlank();
            assertThat(env.getProperty("aws.access-key")).isNotBlank();
            assertThat(env.getProperty("aws.secret-key")).isNotBlank();
        });
    }

    @Test
    @DisplayName("the service's own settings are still read from application.yaml: name, port, schema")
    void serviceSettingsAreKept() {
        runner.run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.application.name")).isEqualTo("order-service");
            assertThat(env.getProperty("server.port")).isEqualTo("8068");
            assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("orders");
            assertThat(env.getProperty("spring.flyway.baseline-on-migrate")).isNull();
        });
    }

    @Test
    @DisplayName("BR-ORD-15: server error detail off; Flyway has no connection of its own (it uses the DataSource)")
    void noServerErrorDetail() {
        runner.run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.hikari.data-source-properties.logServerErrorDetail"))
                    .isEqualTo("false");
            assertThat(env.getProperty("spring.flyway.url")).isNull();
            assertThat(env.getProperty("spring.flyway.user")).isNull();
            assertThat(env.getProperty("spring.flyway.password")).isNull();
        });
    }

    @Test
    @DisplayName("pool 15, scheduler pool 2 (one per job), actuator exposes health and metrics only")
    void poolsAndActuator() {
        runner.run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("15");
            assertThat(env.getProperty("spring.task.scheduling.pool.size")).isEqualTo("2");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,metrics");
        });
    }
}
