package com.hopoong.diag.receiver.quality.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("receiver.compare")
public record CompareProperties(@DefaultValue("false") boolean naiveInsert) {}
