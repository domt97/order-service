package com.dotran.oms.order.support;

import com.dotran.oms.core.logging.MdcProperties;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.order.application.client.OrderQuoteClient;
import com.dotran.oms.order.application.config.CheckoutProperties;
import com.dotran.oms.order.application.config.StockSagaProperties;
import com.dotran.oms.order.application.dto.quote.QuoteResult;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.StockCommandFactory;
import com.dotran.oms.order.application.service.checkout.CheckoutTransactions;
import com.dotran.oms.order.application.service.checkout.PlaceCheckoutService;
import com.dotran.oms.order.application.service.checkout.PlaceCheckoutValidator;
import com.dotran.oms.order.application.service.checkout.PlacementPlanner;
import com.dotran.oms.order.application.service.checkout.QuoteReasonMapper;
import com.dotran.oms.order.infrastructure.config.MessagingConfig;
import com.dotran.oms.order.infrastructure.message.PlaceCheckoutConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Base of the integration tests: one Postgres 16 container for the whole run (wal_level=logical, for {@link WalSlot}),
 * Flyway with the settings of application.yaml (on the application DataSource) into schema {@code orders}, the JPA
 * slice plus the persistence adapters, the checkout and stock saga services and the consumers. The order-quote client
 * is a stub the test sets ({@link #quotes}). No Kafka, no scheduler. Tests run without a surrounding transaction (each
 * call commits); every table is truncated before each test.
 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.show_sql=false",
        // The jobs' services are called by the tests (no scheduler in this slice): no startup grace.
        "app.stock.startup-grace=PT0S"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(PostgresIT.Config.class)
public abstract class PostgresIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("orders")
            .withUsername("postgres")
            .withPassword("postgres")
            // wal_level=logical: the outbox ITs read the WAL with a test_decoding slot (WalSlot), as Debezium would.
            // withCommand replaces the image default, so keep fsync=off.
            .withCommand("postgres", "-c", "fsync=off", "-c", "wal_level=logical");

    static {
        // Started once per JVM, stopped by Testcontainers' Ryuk at the end of the run.
        POSTGRES.start();
    }

    /** What the stub order-quote client answers; reset to "open store" before each test. */
    protected static volatile StubQuotes quotes;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=orders";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected TransactionTemplate tx;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE order_transitions, order_lines, orders, store_day_counters, checkouts, outbox_events");
        quotes = new StubQuotes((store) -> StubQuotes.open());
    }

    /** Runs {@code work} in one committed transaction. A zero-argument lambda lets AssertJ pick the right overload. */
    protected <T> T inTx(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** A settable order-quote answer per store id. */
    public static final class StubQuotes implements OrderQuoteClient {

        private final Function<UUID, QuoteResult> answer;

        public StubQuotes(Function<UUID, QuoteResult> answer) {
            this.answer = answer;
        }

        public static QuoteResult open() {
            return new QuoteResult.Ok(new com.dotran.oms.order.application.dto.quote.OrderQuote("ACTIVE", "VND", true,
                    List.of(), List.of(), "HCM01", "Asia/Ho_Chi_Minh", java.time.LocalDate.of(2026, 11, 16), null));
        }

        @Override
        public QuoteResult quote(UUID merchantId, UUID storeId, List<String> skus, Duration timeLeft) {
            return answer.apply(storeId);
        }
    }

    @TestConfiguration
    @ComponentScan(basePackages = "com.dotran.oms.order.infrastructure.persistence")
    @EnableConfigurationProperties({CheckoutProperties.class, StockSagaProperties.class})
    @Import({CheckoutTransactions.class, PlaceCheckoutService.class, PlaceCheckoutValidator.class,
            QuoteReasonMapper.class, PlacementPlanner.class, CheckoutReplyFactory.class, StockCommandFactory.class,
            PlaceCheckoutConsumer.class, MessagingConfig.class, MdcProperties.class, MdcPropagation.class,
            com.dotran.oms.order.application.service.stock.StockSagaTransactions.class,
            com.dotran.oms.order.application.service.stock.StockReplyService.class,
            com.dotran.oms.order.application.service.stock.ReserveTimeoutService.class,
            com.dotran.oms.order.application.service.stock.StockCommandResendService.class,
            com.dotran.oms.order.infrastructure.message.StockReplyConsumers.class,
            com.dotran.oms.order.infrastructure.metrics.MicrometerSagaMetrics.class})
    static class Config {

        @Bean
        io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @Bean
        OrderQuoteClient orderQuoteClient() {
            // Delegates to the test's current stub.
            return (merchantId, storeId, skus, timeLeft) -> quotes.quote(merchantId, storeId, skus, timeLeft);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }
    }
}
