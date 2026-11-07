package com.hopoong.diag.gateway.ingest.application;
import java.util.UUID;
public record OutboxDraft(UUID eventId, String payloadJson) {}
