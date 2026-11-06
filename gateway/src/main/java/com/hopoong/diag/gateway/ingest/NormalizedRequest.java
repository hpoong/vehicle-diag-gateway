package com.hopoong.diag.gateway.ingest;

public record NormalizedRequest(DiagnosticResultCommand command, String payloadHash, String rawPayload) {}
