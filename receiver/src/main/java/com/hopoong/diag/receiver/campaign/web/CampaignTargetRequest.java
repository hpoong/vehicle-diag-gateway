package com.hopoong.diag.receiver.campaign.web;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import com.hopoong.diag.receiver.quality.web.validation.IsoOffsetDateTime;
public record CampaignTargetRequest(@NotNull @Pattern(regexp="^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$") String eventId, @NotNull @Valid CampaignSourceRequest source, @NotNull @Valid CampaignInfoRequest campaign, @NotNull @Size(min=1,max=5000) List<@NotNull @Valid CampaignVehicleRequest> vehicles) {}
