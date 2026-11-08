package com.hopoong.diag.receiver.campaign.web;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import com.hopoong.diag.receiver.quality.web.validation.IsoOffsetDateTime;
public record CampaignInfoRequest(@NotBlank @Size(max=30) String ecuId, @NotBlank @Size(max=30) String lotNo, @Size(max=10) String dtcCode, String reason, @Size(max=64) String requestedBy, @NotNull @IsoOffsetDateTime String createdAt) {}
