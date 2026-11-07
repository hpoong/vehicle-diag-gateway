package com.hopoong.diag.gateway.ingest.application;

import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;

public record NormalizedRequest(DiagnosticResultCommand command, String payloadHash, String rawPayload) {}
