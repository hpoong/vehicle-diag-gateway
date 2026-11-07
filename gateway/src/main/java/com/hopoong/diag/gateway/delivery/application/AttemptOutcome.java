package com.hopoong.diag.gateway.delivery.application;

public enum AttemptOutcome {
    STARTED, SUCCESS, RETRYABLE, UNCERTAIN, NON_RETRYABLE, ABANDONED, SKIPPED_MAX_ATTEMPTS
}
