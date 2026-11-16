package com.dotran.oms.order.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base of the integration tests: one Postgres 16 container for the whole run, Flyway into schema {@code orders} with the
 * settings of application.yaml, the JPA slice. Tests run without a surrounding transaction (each call commits). Add the
 * persistence adapters and services to an {@code @Import} as they are written, and truncate their tables before each test.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class PostgresIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("orders")
            .withUsername("postgres")
            .withPassword("postgres")
            // wal_level=logical: outbox ITs will read the WAL with a test_decoding slot, as Debezium does.
            // withCommand replaces the image default, so keep fsync=off.
            .withCommand("postgres", "-c", "fsync=off", "-c", "wal_level=logical");

    static {
        // Started once per JVM, stopped by Testcontainers' Ryuk at the end of the run.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=orders";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // application.yaml sets its own Flyway connection: override all three.
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
