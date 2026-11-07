package com.hopoong.diag.gateway.delivery;

import java.time.Duration;

public record RecordResult(State state, Duration nextDelay, String deadReason) {
    public enum State { SENT, RETRY, DEAD, STALE }
}
