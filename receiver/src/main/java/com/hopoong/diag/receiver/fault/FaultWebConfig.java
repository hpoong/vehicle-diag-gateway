package com.hopoong.diag.receiver.fault;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@Profile("chaos")
@RequiredArgsConstructor
public class FaultWebConfig implements WebMvcConfigurer {
    private final FaultState state;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new FaultInterceptor(state)).addPathPatterns("/api/v1/quality-events");
    }
}
