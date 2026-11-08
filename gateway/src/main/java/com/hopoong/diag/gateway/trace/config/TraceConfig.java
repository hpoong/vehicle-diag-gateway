package com.hopoong.diag.gateway.trace.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(TraceProperties.class)
public class TraceConfig {}
