package com.hopoong.diag.gateway.delivery.config;

import com.hopoong.diag.gateway.delivery.application.WorkerIdentity;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class DeliveryPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationAutoConfiguration.class, DeliveryConfig.class);

    @Test void bindsDefaultsAndWorkerIdentity() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var p = context.getBean(DeliveryProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.pollInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(p.batchSize()).isEqualTo(4);
            assertThat(p.lease()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.maxAttempts()).isEqualTo(10);
            assertThat(p.backoff()).isEqualTo(new DeliveryProperties.Backoff(Duration.ofSeconds(5), 2, Duration.ofMinutes(5)));
            assertThat(p.receiverUrl()).isEqualTo("http://localhost:8081");
            assertThat(context.getBean(WorkerIdentity.class).instanceId()).isNotBlank();
        });
    }
    @Test void overridesPropertiesAndTruncatesIdentity() {
        runner.withPropertyValues("gateway.delivery.poll-interval=100ms", "gateway.delivery.backoff.base=0s",
                "gateway.delivery.max-attempts=3", "gateway.delivery.instance-id=gw-1").run(context -> {
            var p = context.getBean(DeliveryProperties.class);
            assertThat(p.pollInterval()).isEqualTo(Duration.ofMillis(100));
            assertThat(p.backoff().base()).isZero();
            assertThat(p.maxAttempts()).isEqualTo(3);
            assertThat(context.getBean(WorkerIdentity.class).workerId("delivery-2")).isEqualTo("gw-1:delivery-2");
        });
        runner.withPropertyValues("gateway.delivery.instance-id=" + "x".repeat(80)).run(context -> {
            var identity = context.getBean(WorkerIdentity.class);
            assertThat(identity.instanceId()).hasSize(64);
            assertThat(identity.workerId("y".repeat(80))).hasSize(100);
        });
    }
    @ParameterizedTest @ValueSource(strings = {"batch-size=0", "max-attempts=0", "backoff.multiplier=0.5",
            "backoff.base=-1s", "backoff.max=1s", "lease=5s", "lease=7s", "receiver-url= ",
            "poll-interval=0s", "connect-timeout=0s", "read-timeout=-1s"})
    void rejectsInvalidSettings(String property) {
        runner.withPropertyValues("gateway.delivery." + property).run(context -> assertThat(context).hasFailed());
    }
}
