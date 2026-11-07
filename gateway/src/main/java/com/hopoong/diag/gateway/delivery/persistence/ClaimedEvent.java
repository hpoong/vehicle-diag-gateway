package com.hopoong.diag.gateway.delivery.persistence;

public record ClaimedEvent(long id, String eventId, String payload, int attemptCount,
                           int cycleNo, String claimToken) {}
