package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.core.logging.MdcProperties;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.message.OutboxPayloadDecoder;
import com.dotran.oms.order.application.command.checkout.PlaceCheckoutCmd;
import com.dotran.oms.order.application.usecase.checkout.PlaceCheckoutUseCase;
import com.dotran.oms.order.infrastructure.config.MessagingConfigTestAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlaceCheckoutConsumer")
class PlaceCheckoutConsumerTest {

    static final String CART = "00000000-0000-0000-0000-0000000ca001";
    static final String MESSAGE = """
            {"eventId":"00000000-0000-0000-0000-0000000ee001","eventType":"PlaceCheckout",
             "occurredAt":"2026-11-16T02:59:55Z","customerId":"00000000-0000-0000-0000-00000000c001",
             "cartId":"%s","cartVersion":3,"requestedAt":"2026-11-16T02:59:55Z",
             "delivery":{"address":{"line1":"1 Le Loi","city":"Ho Chi Minh","country":"VN"},
                         "recipientName":"Nguyen Van A","phone":"0901234567"},
             "stores":[{"merchantId":"00000000-0000-0000-0000-00000000a001",
                        "storeId":"00000000-0000-0000-0000-00000000b001",
                        "quoteId":"00000000-0000-0000-0000-0000000e0001",
                        "lines":[{"lineId":"00000000-0000-0000-0000-0000000f0001",
                                  "productId":"00000000-0000-0000-0000-00000000d001","sku":"TS-M","quantity":2}]}]}
            """.formatted(CART);

    @Mock private PlaceCheckoutUseCase useCase;
    private PlaceCheckoutConsumer consumer;

    @BeforeEach
    void setUp() {
        OutboxPayloadDecoder decoder = new OutboxPayloadDecoder(MessagingConfigTestAccess.decoderMapper(new ObjectMapper()));
        consumer = new PlaceCheckoutConsumer(decoder, useCase, new MdcPropagation(new MdcProperties()));
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("PlaceCheckout", 0, 7L, CART, value);
    }

    @Test
    @DisplayName("maps every field; the Debezium double-encoded form is accepted too")
    void maps() throws Exception {
        consumer.consume(record(MESSAGE));
        consumer.consume(record(new ObjectMapper().writeValueAsString(MESSAGE)));
        ArgumentCaptor<PlaceCheckoutCmd> cmd = ArgumentCaptor.forClass(PlaceCheckoutCmd.class);
        verify(useCase, org.mockito.Mockito.times(2)).place(cmd.capture());
        PlaceCheckoutCmd c = cmd.getValue();
        assertThat(c.cartId().getValue()).isEqualTo(UUID.fromString(CART));
        assertThat(c.cartVersion()).isEqualTo(3);
        assertThat(c.requestedAt()).isEqualTo(Instant.parse("2026-11-16T02:59:55Z"));
        assertThat(c.delivery().address().country()).isEqualTo("VN");
        assertThat(c.stores()).singleElement().satisfies(s -> {
            assertThat(s.storeId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000b001"));
            assertThat(s.lines()).singleElement().satisfies(l -> {
                assertThat(l.sku()).isEqualTo("TS-M");
                assertThat(l.quantity()).isEqualTo(2);
            });
        });
    }

    @Test
    @DisplayName("wrong eventType or a missing / invalid key field → InvalidPlaceCheckoutMessageException, no call")
    void invalidKeyFields() {
        for (String bad : new String[]{
                MESSAGE.replace("\"eventType\":\"PlaceCheckout\"", "\"eventType\":\"Other\""),
                MESSAGE.replace("\"cartId\":\"" + CART + "\",", ""),
                MESSAGE.replace("\"cartId\":\"" + CART + "\"", "\"cartId\":\"1-2-3-4-5\""),
                MESSAGE.replace("\"cartVersion\":3", "\"cartVersion\":-1"),
                MESSAGE.replace("\"cartVersion\":3", "\"cartVersion\":3.7"),
                MESSAGE.replace("\"cartVersion\":3", "\"cartVersion\":1e30"),
                MESSAGE.replace("\"customerId\":\"00000000-0000-0000-0000-00000000c001\",", "")}) {
            assertThatThrownBy(() -> consumer.consume(record(bad))).isInstanceOf(InvalidPlaceCheckoutMessageException.class);
        }
        verify(useCase, never()).place(any());
    }

    @Test
    @DisplayName("a bad nested id or instant becomes null (the validator answers INVALID_REQUEST), not a DLT")
    void lenientNested() throws Exception {
        consumer.consume(record(MESSAGE.replace("\"storeId\":\"00000000-0000-0000-0000-00000000b001\"",
                "\"storeId\":\"nope\"").replace("\"requestedAt\":\"2026-11-16T02:59:55Z\"",
                "\"requestedAt\":\"yesterday\"")));
        ArgumentCaptor<PlaceCheckoutCmd> cmd = ArgumentCaptor.forClass(PlaceCheckoutCmd.class);
        verify(useCase).place(cmd.capture());
        assertThat(cmd.getValue().stores().get(0).storeId()).isNull();
        assertThat(cmd.getValue().requestedAt()).isNull();
    }

    @Test
    @DisplayName("a whole-valued decimal cartVersion (3.0) is 3; a non-whole quantity (1.5) becomes null → INVALID_REQUEST")
    void numbers() throws Exception {
        consumer.consume(record(MESSAGE.replace("\"cartVersion\":3", "\"cartVersion\":3.0")
                .replace("\"quantity\":2", "\"quantity\":1.5")));
        ArgumentCaptor<PlaceCheckoutCmd> cmd = ArgumentCaptor.forClass(PlaceCheckoutCmd.class);
        verify(useCase).place(cmd.capture());
        assertThat(cmd.getValue().cartVersion()).isEqualTo(3);
        assertThat(cmd.getValue().stores().get(0).lines().get(0).quantity()).isNull();
    }

    @Test
    @DisplayName("unreadable JSON → JsonProcessingException; a tombstone is ignored")
    void unreadable() throws Exception {
        assertThatThrownBy(() -> consumer.consume(record("{not json"))).isInstanceOf(JsonProcessingException.class);
        consumer.consume(record(null));
        verify(useCase, never()).place(any());
    }
}
