package com.hopoong.diag.gateway.delivery.application;

public record RunSummary(int claimed, int sent, int retry, int dead, int stale, int failed) {}
