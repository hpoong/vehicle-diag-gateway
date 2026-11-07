package com.hopoong.diag.receiver.fault;

public record FaultConfig(FaultMode mode, long holdMs) {
    public static final long DEFAULT_HOLD_MS = 15_000;
}
