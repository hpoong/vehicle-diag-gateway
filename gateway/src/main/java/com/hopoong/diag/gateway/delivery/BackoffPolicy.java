package com.hopoong.diag.gateway.delivery;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BackoffPolicy {
    private final DeliveryProperties.Backoff config;
    private final LongUnaryOperator random;

    @Autowired
    public BackoffPolicy(DeliveryProperties properties) {
        this(properties.backoff(), bound -> bound == Long.MAX_VALUE
                ? ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE
                : ThreadLocalRandom.current().nextLong(bound + 1));
    }

    BackoffPolicy(DeliveryProperties.Backoff config, LongUnaryOperator random) {
        this.config = config;
        this.random = random;
    }

    public Duration delay(int failedAttempt) {
        if (failedAttempt < 1) throw new IllegalArgumentException("failedAttempt must be positive");
        long base = config.base().toMillis();
        if (base == 0) return Duration.ZERO;
        long raw = (long) Math.min(base * Math.pow(config.multiplier(), failedAttempt - 1), config.max().toMillis());
        long half = raw / 2;
        return Duration.ofMillis(half + random.applyAsLong(raw - half));
    }
}
