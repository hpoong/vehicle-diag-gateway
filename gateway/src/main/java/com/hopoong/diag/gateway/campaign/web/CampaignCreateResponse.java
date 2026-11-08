package com.hopoong.diag.gateway.campaign.web;
public record CampaignCreateResponse(long campaignId,String requestId,int targetCount,String result,Delivery delivery) {
    public record Delivery(String eventId,String status) {}
}
