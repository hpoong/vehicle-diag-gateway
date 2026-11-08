package com.hopoong.diag.gateway.campaign.persistence;
import java.time.OffsetDateTime;
public record CampaignDetailRow(long id, String requestId, String ecuId, String lotNo, String dtcCode, String reason, String requestedBy, int targetCount, OffsetDateTime createdAt, long outboxId, String eventId, String status, int attemptCount, int cycleNo, String lastOutcome, Integer lastHttpStatus, OffsetDateTime nextAttemptAt, OffsetDateTime sentAt, String deadReason) {}
