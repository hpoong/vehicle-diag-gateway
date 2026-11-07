package com.hopoong.diag.gateway.delivery.application;

import java.time.Duration;

public record RecordResult(State state, Duration nextDelay, String deadReason) {
    public enum State { SENT, RETRY, DEAD, STALE }
}
