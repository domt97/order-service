package com.dotran.oms.order.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code app.checkout} (application.yaml): STALE window (ADR 0007), accepted clock skew of requestedAt, and the time
 * for all order-quote calls of one command.
 */
@ConfigurationProperties("app.checkout")
public record CheckoutProperties(Duration staleAfter, Duration clockSkew, Duration quoteBudget) {

    public CheckoutProperties {
        staleAfter = staleAfter == null ? Duration.ofMinutes(10) : staleAfter;
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
        quoteBudget = quoteBudget == null ? Duration.ofSeconds(20) : quoteBudget;
        if (staleAfter.isNegative() || staleAfter.isZero() || staleAfter.compareTo(Duration.ofMinutes(15)) >= 0) {
            throw new IllegalArgumentException("app.checkout.stale-after must be > 0 and shorter than the 15-min hold");
        }
        if (clockSkew.isNegative() || quoteBudget.isNegative() || quoteBudget.isZero()) {
            throw new IllegalArgumentException("app.checkout.clock-skew >= 0 and quote-budget > 0 required");
        }
    }
}
