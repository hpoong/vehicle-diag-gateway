package com.hopoong.diag.receiver.campaign.web;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import com.hopoong.diag.receiver.quality.web.validation.IsoOffsetDateTime;
public record CampaignSourceRequest(String system, @NotNull @Positive Long campaignId, String requestId) {}
