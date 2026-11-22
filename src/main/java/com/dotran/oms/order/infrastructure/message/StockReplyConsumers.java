package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.core.message.OutboxPayloadDecoder;
import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.application.usecase.stock.HandleStockReplyUseCase;
import com.dotran.oms.order.infrastructure.message.dto.StockReplyMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inventory-service's replies to the stock commands (ADR 0006), keyed by orderId, one listener and group per topic.
 * Each only maps and calls the use case, which is idempotent by the order's state. On by default (they must run even
 * while PlaceCheckout is off, so a started saga always ends); {@code app.kafka.stock-replies.enabled=false} turns them
 * off for a local run without Kafka. Poll sizing: see app.kafka.stock-replies in application.yaml.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StockReplyConsumers {

    /** MDC key of the order (oms-core MdcKeys has none yet). */
    static final String ORDER_ID = "orderId";

    private final OutboxPayloadDecoder payloadDecoder;
    private final HandleStockReplyUseCase useCase;
    private final MdcPropagation mdcPropagation;

    // Topics are literals: oms-workspace scripts/extract_facts.py reads them from the source.

    @KafkaListener(topics = "StockReserved", groupId = "order-stock-reserved",
            autoStartup = "${app.kafka.stock-replies.enabled:true}", properties = "max.poll.records=5")
    public void reserved(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, StockReplyCmd.Type.RESERVED);
    }

    @KafkaListener(topics = "StockReservationRejected", groupId = "order-stock-reservation-rejected",
            autoStartup = "${app.kafka.stock-replies.enabled:true}", properties = "max.poll.records=5")
    public void rejected(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, StockReplyCmd.Type.RESERVATION_REJECTED);
    }

    @KafkaListener(topics = "StockReservationConfirmed", groupId = "order-stock-reservation-confirmed",
            autoStartup = "${app.kafka.stock-replies.enabled:true}", properties = "max.poll.records=5")
    public void confirmed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, StockReplyCmd.Type.CONFIRMED);
    }

    @KafkaListener(topics = "StockReservationConfirmationFailed",
            groupId = "order-stock-reservation-confirmation-failed",
            autoStartup = "${app.kafka.stock-replies.enabled:true}", properties = "max.poll.records=5")
    public void confirmationFailed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, StockReplyCmd.Type.CONFIRMATION_FAILED);
    }

    @KafkaListener(topics = "StockReservationReleased", groupId = "order-stock-reservation-released",
            autoStartup = "${app.kafka.stock-replies.enabled:true}", properties = "max.poll.records=5")
    public void released(ConsumerRecord<String, String> record) throws JsonProcessingException {
        handle(record, StockReplyCmd.Type.RELEASED);
    }

    void handle(ConsumerRecord<String, String> record, StockReplyCmd.Type type) throws JsonProcessingException {
        try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
            if (record.value() == null) {
                log.warn("{} record without a value (tombstone); ignored", record.topic());
                return;
            }
            StockReplyMessage message = payloadDecoder.decode(record.value(), StockReplyMessage.class);
            StockReplyCmd cmd = StockReplyMapping.toCmd(record.topic(), type, message);
            mdc.put(MdcKeys.EVENT_ID, cmd.eventId().getValue());
            mdc.put(ORDER_ID, cmd.orderId().getValue());
            HandleStockReplyUseCase.Outcome outcome = useCase.handle(cmd);
            log.info("{} handled: eventId={} orderId={} outcome={}", record.topic(), cmd.eventId().getValue(),
                    cmd.orderId().getValue(), outcome);
        }
    }
}
