package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.application.repository.OutboxEventRepository;
import com.dotran.oms.order.support.PostgresIT;
import com.dotran.oms.order.support.WalSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxSqlIT extends PostgresIT {

    @Autowired
    private OutboxEventRepository outbox;

    private WalSlot wal;

    @BeforeEach
    void slot() {
        wal = WalSlot.create(jdbc, "order_outbox_it");
    }

    @AfterEach
    void dropSlot() {
        wal.drop();
    }

    private static OutboxMessage message(UUID cartId) {
        UUID eventId = UUID.randomUUID();
        return new OutboxMessage(eventId, UUID.randomUUID(), null, "Checkout", cartId, "CheckoutRejected",
                Map.of("eventId", eventId.toString(), "eventType", "CheckoutRejected"));
    }

    @Test
    @DisplayName("one INSERT and one DELETE in the WAL, in one transaction, with payload, key and requestId; table empty")
    void insertAndDelete() {
        UUID cart = UUID.randomUUID();
        OutboxMessage message = message(cart);
        String requestId = UUID.randomUUID().toString();
        try (MdcScope mdc = MdcScope.open().put(MdcKeys.REQUEST_ID, requestId)) {
            tx.executeWithoutResult(status -> outbox.saveAll(List.of(message)));
        }
        List<WalSlot.Change> changes = wal.changes().stream().filter(WalSlot.Change::isOutbox).toList();
        assertThat(changes).extracting(WalSlot.Change::op).containsExactly("INSERT", "DELETE");
        assertThat(changes).extracting(WalSlot.Change::xid).containsOnly(changes.get(0).xid());
        assertThat(changes.get(0).id()).isEqualTo(message.eventId().toString());
        assertThat(changes.get(0).data()).contains("aggregate_id[uuid]:'" + cart + "'")
                .contains("event_type[character varying]:'CheckoutRejected'")
                .contains("merchant_id[uuid]:null");
        assertThat(changes.get(0).correlation()).contains(requestId);
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    @Test
    @DisplayName("a rolled-back transaction leaves nothing in the WAL")
    void rollback() {
        tx.executeWithoutResult(status -> {
            outbox.saveAll(List.of(message(UUID.randomUUID())));
            status.setRollbackOnly();
        });
        assertThat(wal.changes().stream().filter(WalSlot.Change::isOutbox)).isEmpty();
    }

    @Test
    @DisplayName("outside a transaction it refuses to write (MANDATORY)")
    void mandatory() {
        assertThatThrownBy(() -> outbox.saveAll(List.of(message(UUID.randomUUID()))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
