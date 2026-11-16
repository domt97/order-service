package com.dotran.oms.order.infrastructure.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** {@code app.clients.stores}: where stores-service is and how long one call may take (capped by the quote budget). */
@ConfigurationProperties("app.clients.stores")
public record StoresClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    public StoresClientProperties {
        baseUrl = baseUrl == null ? "http://localhost:8048" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
    }
}
