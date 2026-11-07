package com.hopoong.diag.gateway.delivery;

public record SendResult(Integer httpStatus, Throwable error, long latencyMs) {
    public static SendResult responded(int status, long ms) { return new SendResult(status, null, ms); }
    public static SendResult failed(Throwable error, long ms) { return new SendResult(null, error, ms); }
}
