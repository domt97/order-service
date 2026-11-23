package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.order.application.command.stock.StockReplyCmd;
import com.dotran.oms.order.application.service.stock.InvalidStockReplyException;
import com.dotran.oms.order.infrastructure.message.dto.StockReplyMessage;
import com.dotran.oms.order.support.ContractSchemas;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One sample per reply type validates against its copied schema and maps to a command; bad records are refused. */
@DisplayName("Stock reply contracts and mapping")
class StockReplyContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDS = """
            "eventId":"%s","occurredAt":"2026-11-21T03:00:00Z","merchantId":"%s","storeId":"%s","orderId":"%s",
            "causationId":"%s"
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID());
    private static final String ITEM = UUID.randomUUID().toString();

    static String sample(String type) {
        String body = switch (type) {
            case "StockReserved" -> """
                    ,"expiresAt":"2026-11-21T03:15:00Z",
                    "lines":[{"orderItemId":"%s","sku":"TS-M","quantity":2,"expiresAt":"2026-11-21T03:15:00Z"}]
                    """.formatted(ITEM);
            case "StockReservationRejected" -> """
                    ,"orderReason":null,"lines":[{"orderItemId":"%s","sku":"TS-M","reason":"INSUFFICIENT_STOCK"}]
                    """.formatted(ITEM);
            case "StockReservationConfirmed" -> """
                    ,"lines":[{"orderItemId":"%s","status":"CONFIRMED"}]""".formatted(ITEM);
            case "StockReservationConfirmationFailed" -> """
                    ,"reason":"NOT_RESERVED","lines":[{"orderItemId":"%s","status":"EXPIRED"}]""".formatted(ITEM);
            case "StockReservationReleased" -> """
                    ,"restock":true,"lines":[]""";
            default -> throw new IllegalArgumentException(type);
        };
        return "{\"eventType\":\"" + type + "\"," + IDS + body + "}";
    }

    private static StockReplyMessage message(String json) throws Exception {
        return JSON.readValue(json, StockReplyMessage.class);
    }

    @Test
    @DisplayName("every reply sample validates and maps; StockReserved carries expiresAt and quantities")
    void samples() throws Exception {
        String[] types = {"StockReserved", "StockReservationRejected", "StockReservationConfirmed",
                "StockReservationConfirmationFailed", "StockReservationReleased"};
        StockReplyCmd.Type[] cmdTypes = StockReplyCmd.Type.values();
        for (int i = 0; i < types.length; i++) {
            String json = sample(types[i]);
            assertThat(ContractSchemas.validate(types[i], JSON.readTree(json))).as(types[i]).isEmpty();
            StockReplyCmd cmd = StockReplyMapping.toCmd(types[i], cmdTypes[i], message(json));
            assertThat(cmd.type()).isEqualTo(cmdTypes[i]);
        }
        StockReplyCmd reserved = StockReplyMapping.toCmd("StockReserved", StockReplyCmd.Type.RESERVED,
                message(sample("StockReserved")));
        assertThat(reserved.expiresAt()).isNotNull();
        assertThat(reserved.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(2));
    }

    @Test
    @DisplayName("wrong eventType, missing or bad ids, a non-whole quantity → InvalidStockReplyException naming the field")
    void refused() throws Exception {
        assertThatThrownBy(() -> StockReplyMapping.toCmd("StockReserved", StockReplyCmd.Type.RESERVED,
                message(sample("StockReservationConfirmed")))).isInstanceOf(InvalidStockReplyException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> StockReplyMapping.toCmd("StockReservationReleased", StockReplyCmd.Type.RELEASED,
                message(sample("StockReservationReleased").replaceFirst("\"orderId\":\"[0-9a-f-]+\"",
                        "\"orderId\":\"x\"")))).isInstanceOf(InvalidStockReplyException.class)
                .hasMessage("orderId not a uuid");
        assertThatThrownBy(() -> StockReplyMapping.toCmd("StockReservationReleased", StockReplyCmd.Type.RELEASED,
                message(sample("StockReservationReleased").replaceFirst("\"merchantId\":\"[0-9a-f-]+\",", ""))))
                .isInstanceOf(InvalidStockReplyException.class).hasMessage("merchantId missing");
        assertThatThrownBy(() -> StockReplyMapping.toCmd("StockReserved", StockReplyCmd.Type.RESERVED,
                message(sample("StockReserved").replace("\"quantity\":2", "\"quantity\":2.5"))))
                .isInstanceOf(InvalidStockReplyException.class).hasMessageContaining("quantity");
    }
}
