package com.hopoong.diag.gateway.delivery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryConfig {
    @Bean
    WorkerIdentity workerIdentity(DeliveryProperties properties) {
        return new WorkerIdentity(properties);
    }
    @Bean
    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor deliveryExecutor(DeliveryProperties properties) {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.batchSize());
        executor.setMaxPoolSize(properties.batchSize());
        executor.setThreadNamePrefix("delivery-");
        return executor;
    }
}

