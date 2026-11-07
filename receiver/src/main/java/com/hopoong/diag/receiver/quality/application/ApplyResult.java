package com.hopoong.diag.receiver.quality.application;

public record ApplyResult(Outcome outcome, int caseCount) {
    public enum Outcome { APPLIED, DUPLICATE, CONFLICT }
}
