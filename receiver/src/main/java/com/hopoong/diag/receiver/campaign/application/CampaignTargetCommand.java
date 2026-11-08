package com.hopoong.diag.receiver.campaign.application;
import com.hopoong.diag.receiver.campaign.web.CampaignVehicleRequest;
import java.util.List;
public record CampaignTargetCommand(String eventId,long campaignId,String ecuId,String lotNo,List<CampaignVehicleRequest> vehicles) {}
