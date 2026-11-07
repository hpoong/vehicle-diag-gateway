package com.hopoong.diag.receiver.quality.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SourceRequest(String system, @NotNull @Positive Long resultId,
        String deviceId, String deviceEventId, String policyCode) {}
