package com.hopoong.diag.gateway.delivery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("gateway.delivery")
@Validated
public record DeliveryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("4") @Min(1) int batchSize,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue("10") @Min(1) int maxAttempts,
        @DefaultValue @Valid Backoff backoff,
        @DefaultValue("http://localhost:8081") @NotBlank String receiverUrl,
        @DefaultValue("") String instanceId) {

    @AssertTrue(message = "poll interval, lease and timeouts must be positive milliseconds")
    public boolean isTimingValid() {
        return positive(pollInterval) && positive(lease) && positive(connectTimeout) && positive(readTimeout);
    }

    @AssertTrue(message = "lease must exceed connect timeout plus read timeout")
    public boolean isLeaseLongerThanRequestTimeout() {
        return lease != null && connectTimeout != null && readTimeout != null
                && lease.compareTo(connectTimeout.plus(readTimeout)) > 0;
    }

    private static boolean positive(Duration value) {
        return value != null && value.toMillis() > 0;
    }

    public record Backoff(@DefaultValue("5s") Duration base,
                          @DefaultValue("2.0") @DecimalMin("1.0") double multiplier,
                          @DefaultValue("5m") Duration max) {
        @AssertTrue(message = "backoff requires 0 <= base <= max and a finite multiplier")
        public boolean isRangeValid() {
            return base != null && max != null && !base.isNegative()
                    && max.compareTo(base) >= 0 && Double.isFinite(multiplier);
        }
    }
}
