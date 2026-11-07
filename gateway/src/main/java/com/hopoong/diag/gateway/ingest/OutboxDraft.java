package com.hopoong.diag.gateway.ingest;
import java.util.UUID;
public record OutboxDraft(UUID eventId, String payloadJson) {}
