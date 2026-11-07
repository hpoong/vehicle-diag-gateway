package com.hopoong.diag.gateway.delivery;

public record DeliveryOutcome(AttemptOutcome outcome, Integer httpStatus, String errorCode,
                              String errorMessage, long latencyMs) {}
