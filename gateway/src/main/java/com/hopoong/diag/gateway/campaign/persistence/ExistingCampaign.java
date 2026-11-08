package com.hopoong.diag.gateway.campaign.persistence;
import java.time.OffsetDateTime;
public record ExistingCampaign(long id, String requestHash, int targetCount, String eventId, String deliveryStatus) {}
