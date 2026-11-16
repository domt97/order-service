package com.dotran.oms.order.infrastructure.message;

import com.dotran.oms.core.logging.MdcKeys;
import com.dotran.oms.core.logging.MdcPropagation;
import com.dotran.oms.core.logging.MdcScope;
import lombok.experimental.UtilityClass;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.util.Map;

/**
 * MDC for one Kafka record (copy of inventory-service's). The producer's correlation keys come from the
 * {@code correlationContext} header (ADR 0004), so {@code requestId} is the id of the whole flow; without a valid header
 * it falls back to {@code topic-partition-offset}. Open it first in every listener; the DLT recoverer opens it too.
 * Outbound HTTP inside the scope sends this {@code requestId} as {@code X-Request-Id} (oms-core RequestIdClientConfig).
 */
@UtilityClass
public class KafkaMdc {

    public static MdcScope open(ConsumerRecord<?, ?> record, MdcPropagation propagation) {
        MdcScope scope = MdcScope.open()
                .put(MdcKeys.KAFKA_TOPIC, record.topic())
                .put(MdcKeys.KAFKA_PARTITION, record.partition())
                .put(MdcKeys.KAFKA_OFFSET, record.offset());

        Header header = record.headers() == null ? null : record.headers().lastHeader(MdcPropagation.HEADER);
        Map<String, String> propagated = propagation.restore(header == null ? null : header.value());
        propagated.forEach(scope::put);
        if (!propagated.containsKey(MdcKeys.REQUEST_ID)) {
            scope.put(MdcKeys.REQUEST_ID, record.topic() + "-" + record.partition() + "-" + record.offset());
        }
        return scope;
    }
}
