package com.hopoong.diag.gateway.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "gateway.delivery", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class DeliveryScheduler implements SchedulingConfigurer {
    private final DeliveryService service;
    private final DeliveryProperties properties;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::tick, properties.pollInterval());
    }

    private void tick() {
        try { service.runOnce(); }
        catch (Exception ex) { log.error("DELIVERY_POLL_FAILED", ex); }
    }
}
