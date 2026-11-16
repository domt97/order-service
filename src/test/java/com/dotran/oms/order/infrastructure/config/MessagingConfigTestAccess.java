package com.dotran.oms.order.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Test access to the package-private decoder mapper and error-handler helpers. */
public final class MessagingConfigTestAccess {

    private MessagingConfigTestAccess() {
    }

    public static ObjectMapper decoderMapper(ObjectMapper base) {
        return MessagingConfig.withoutSourceInErrors(base);
    }
}
