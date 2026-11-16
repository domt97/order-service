package com.dotran.oms.order.infrastructure.persistence;

import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.event.CheckoutReplyFactory;
import com.dotran.oms.order.application.event.OutboxMessage;
import com.dotran.oms.order.common.id.CheckoutId;
import com.dotran.oms.order.domain.enums.RejectionCode;
import com.dotran.oms.order.domain.valueobject.RejectionReason;
import com.dotran.oms.order.support.Checkouts;
import com.dotran.oms.order.support.ContractSchemas;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dotran.oms.order.support.Checkouts.NOW;
import static com.dotran.oms.order.support.Checkouts.STORE;
import static org.assertj.core.api.Assertions.assertThat;

/** The outbox payload as Debezium publishes it (OutboxPayloadSerializer) against the copied contracts. */
@DisplayName("CheckoutRejected / PlaceCheckout contracts")
class CheckoutRejectedContractTest {

    private final OutboxPayloadSerializer serializer = new OutboxPayloadSerializer();
    private final CheckoutReplyFactory factory = new CheckoutReplyFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(OutboxMessage message) throws Exception {
        return mapper.readTree(serializer.toJson(message.payload()));
    }

    @Test
    @DisplayName("a rejection with checkout-, store- and line-level reasons validates; absent ids are left out")
    void rejected() throws Exception {
        PlaceCheckoutCmd cmd = Checkouts.valid().build();
        JsonNode payload = json(factory.rejected(cmd, CheckoutId.newId(), List.of(
                RejectionReason.checkout(RejectionCode.INVALID_REQUEST),
                RejectionReason.store(RejectionCode.STORE_CLOSED, STORE),
                RejectionReason.line(RejectionCode.UNKNOWN_SKU, STORE, "TS-M")), NOW));
        assertThat(ContractSchemas.validate("CheckoutRejected", payload)).isEmpty();
        assertThat(payload.get("reasons").get(0).has("storeId")).isFalse();
        assertThat(payload.get("reasons").get(0).has("sku")).isFalse();
        assertThat(payload.get("checkoutId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("CONTENT_MISMATCH validates and has no checkoutId")
    void mismatch() throws Exception {
        JsonNode payload = json(factory.contentMismatch(Checkouts.valid().build(), NOW));
        assertThat(ContractSchemas.validate("CheckoutRejected", payload)).isEmpty();
        assertThat(payload.has("checkoutId")).isFalse();
        assertThat(payload.get("reasons").get(0).get("code").asText()).isEqualTo("CONTENT_MISMATCH");
    }

    @Test
    @DisplayName("PlaceCheckout: the sample the consumer test uses is valid; a SKU with NUL or over 100 characters is not")
    void placeCheckoutSample() throws Exception {
        String sample = com.dotran.oms.order.infrastructure.message.PlaceCheckoutConsumerTestAccess.sample();
        assertThat(ContractSchemas.validate("PlaceCheckout", mapper.readTree(sample))).isEmpty();
        assertThat(ContractSchemas.validate("PlaceCheckout", mapper.readTree(sample.replace("\"TS-M\"",
                "\"TS\\u0000M\"")))).isNotEmpty();
        assertThat(ContractSchemas.validate("PlaceCheckout", mapper.readTree(sample.replace("\"TS-M\"",
                "\"" + "X".repeat(101) + "\"")))).isNotEmpty();
        assertThat(ContractSchemas.validate("PlaceCheckout", mapper.readTree(sample.replace("\"TS-M\"",
                "\"   \"")))).isNotEmpty();
        assertThat(ContractSchemas.validate("PlaceCheckout", mapper.readTree(sample.replace("\"TS-M\"",
                "\"A B\"")))).isEmpty();
    }
}
