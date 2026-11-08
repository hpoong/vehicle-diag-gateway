package com.hopoong.diag.gateway.campaign.application;
import com.hopoong.diag.gateway.campaign.persistence.ExistingCampaign;
public record CreateOutcome(boolean created,long campaignId,int targetCount,String eventId,String deliveryStatus,String requestHash) {
    public static CreateOutcome existing(ExistingCampaign c) {return new CreateOutcome(false,c.id(),c.targetCount(),c.eventId(),c.deliveryStatus(),c.requestHash());}
    public static CreateOutcome created(long id,int count,String event) {return new CreateOutcome(true,id,count,event,"PENDING",null);}
}
