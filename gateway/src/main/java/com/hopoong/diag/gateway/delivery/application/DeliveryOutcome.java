package com.hopoong.diag.gateway.delivery.application;

public record DeliveryOutcome(AttemptOutcome outcome, Integer httpStatus, String errorCode,
                              String errorMessage, long latencyMs) {}
