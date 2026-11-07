package com.hopoong.diag.gateway.ingest.domain;

import java.time.OffsetDateTime;
import java.util.List;

public record DiagnosticResultCommand(String deviceId, String eventId, String brand, String vin,
        String sessionId, OffsetDateTime occurredAt, List<EcuCommand> ecus) {}
