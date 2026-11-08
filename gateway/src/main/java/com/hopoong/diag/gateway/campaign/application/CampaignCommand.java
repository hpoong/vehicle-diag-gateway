package com.hopoong.diag.gateway.campaign.application;
public record CampaignCommand(String requestId, String ecuId, String lotNo, String dtcCode, String reason, String requestedBy) {}
