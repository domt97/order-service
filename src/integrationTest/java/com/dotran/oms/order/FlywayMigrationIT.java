package com.dotran.oms.order;

import com.dotran.oms.order.support.PostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Flyway with the settings of application.yaml on an empty database: everything lands in schema {@code orders}. */
class FlywayMigrationIT extends PostgresIT {

    @Test
    @DisplayName("Flyway creates schema orders and keeps its history table there")
    void schemaAndHistoryInOrders() {
        assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'orders'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'orders' AND table_name = 'flyway_schema_history'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("orders");
    }

    @Test
    @DisplayName("nothing is created in schema public")
    void nothingInPublic() {
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")).isZero();
    }
}
