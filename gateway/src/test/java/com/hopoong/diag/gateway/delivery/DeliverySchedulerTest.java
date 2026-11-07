package com.hopoong.diag.gateway.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class DeliverySchedulerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ValidationAutoConfiguration.class, DeliveryConfig.class, DeliveryScheduler.class)
            .withBean(DeliveryService.class, () -> mock(DeliveryService.class));
    @Test void enablesByDefaultAndDisablesExplicitly() {
        runner.run(c -> assertThat(c).hasSingleBean(DeliveryScheduler.class));
        runner.withPropertyValues("gateway.delivery.enabled=true").run(c -> assertThat(c).hasSingleBean(DeliveryScheduler.class));
        runner.withPropertyValues("gateway.delivery.enabled=false").run(c -> assertThat(c).doesNotHaveBean(DeliveryScheduler.class));
    }
}
