package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import com.dotran.oms.core.message.OutboxPayloadDecoder;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.usecase.checkout.PlaceCheckoutUseCase;
import com.dotran.oms.order.infrastructure.message.dto.PlaceCheckoutMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Entry point of the PlaceCheckout command (ADR 0007), sent by cart-service's outbox, key cartId. Only maps and calls
 * the use case: it is idempotent by the checkout key and writes its reply to the outbox, so a business outcome is a
 * normal return and every exception goes to the error handler unchanged. Off unless
 * {@code app.kafka.place-checkout.enabled} (placement is not built yet).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlaceCheckoutConsumer {

    private final OutboxPayloadDecoder payloadDecoder;
    private final PlaceCheckoutUseCase placeCheckoutUseCase;
    private final MdcPropagation mdcPropagation;

    @KafkaListener(
            // A literal: oms-workspace scripts/extract_facts.py reads it from the source.
            topics = "PlaceCheckout",
            groupId = "order-place-checkout",
            autoStartup = "${app.kafka.place-checkout.enabled:false}",
            // One record per poll: see app.kafka.place-checkout in application.yaml (worst case 170 s < 300 s).
            properties = "max.poll.records=1"
    )
    public void consume(ConsumerRecord<String, String> record) throws JsonProcessingException {
        try (MdcScope mdc = KafkaMdc.open(record, mdcPropagation)) {
            if (record.value() == null) {
                log.warn("PlaceCheckout record without a value (tombstone); ignored");
                return;
            }
            PlaceCheckoutMessage message = payloadDecoder.decode(record.value(), PlaceCheckoutMessage.class);
            PlaceCheckoutCmd cmd = PlaceCheckoutMapping.toCmd(message);
            // The validated UUID, never the raw text.
            mdc.put(MdcKeys.EVENT_ID, cmd.eventId().getValue());

            // Inside the scope: the reply's correlation context and the X-Request-Id of the quote calls come from it.
            PlaceCheckoutUseCase.PlaceCheckoutOutcome outcome = placeCheckoutUseCase.place(cmd);
            log.info("PlaceCheckout handled: eventId={} cartId={} cartVersion={} outcome={}",
                    cmd.eventId().getValue(), cmd.cartId().getValue(), cmd.cartVersion(), outcome);
        }
    }
}
