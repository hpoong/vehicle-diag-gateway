package com.hopoong.diag.gateway.delivery;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BackoffPolicyTest {
    final DeliveryProperties.Backoff config = new DeliveryProperties.Backoff(Duration.ofSeconds(5), 2, Duration.ofMinutes(5));
    @Test void calculatesEqualJitterBoundsAndCap() {
        var min = new BackoffPolicy(config, bound -> 0);
        var max = new BackoffPolicy(config, bound -> bound);
        int[] attempts = {1,2,3,6,7,9,1000};
        long[] raw = {5000,10000,20000,160000,300000,300000,300000};
        for (int i=0; i<attempts.length; i++) {
            assertThat(min.delay(attempts[i])).isEqualTo(Duration.ofMillis(raw[i]/2));
            assertThat(max.delay(attempts[i])).isEqualTo(Duration.ofMillis(raw[i]));
        }
        assertThatThrownBy(() -> min.delay(0)).isInstanceOf(IllegalArgumentException.class);
        var zero = new BackoffPolicy(new DeliveryProperties.Backoff(Duration.ZERO, 2, Duration.ofMinutes(5)), b -> b);
        assertThat(zero.delay(1000)).isZero();
    }
    @Test void productionRandomStaysWithinBounds() {
        var p = new DeliveryProperties(true, Duration.ofMillis(500), 4, Duration.ofSeconds(30),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 10, config, "http://localhost:8081", "test");
        var policy = new BackoffPolicy(p);
        for (int n=1; n<=10; n++) {
            long raw = Math.min(5000L << (n-1), 300000);
            for (int i=0; i<100; i++) assertThat(policy.delay(n).toMillis()).isBetween(raw/2, raw);
        }
    }
}
