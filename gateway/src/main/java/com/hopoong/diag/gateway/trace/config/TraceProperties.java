package com.hopoong.diag.gateway.trace.config;
import java.time.Duration;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
@ConfigurationProperties("gateway.trace")
@Validated
public record TraceProperties(@DefaultValue("20") @Min(1) int minSample,
        @DefaultValue("90d") Duration maxWindow, @DefaultValue("30d") Duration defaultWindow) {}
