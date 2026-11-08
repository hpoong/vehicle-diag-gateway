package com.hopoong.diag.gateway.campaign.web;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.hopoong.diag.gateway.campaign.persistence.*;
import java.time.*;
import java.util.List;
public record CampaignView(long campaignId,String requestId,String ecuId,String lotNo,String dtcCode,String reason,String requestedBy,
        int targetCount,Instant createdAt,DeliveryView delivery,@JsonInclude(JsonInclude.Include.NON_NULL) List<SnapshotVehicle> vehicles) {
    public record DeliveryView(long outboxId,String eventId,String status,int attemptCount,int cycleNo,String lastOutcome,Integer lastHttpStatus,Instant nextAttemptAt,Instant sentAt,String deadReason) {}
    private static Instant instant(OffsetDateTime value) {return value==null?null:value.toInstant();}
    public static CampaignView of(CampaignDetailRow r,List<SnapshotVehicle> vehicles) {
        return new CampaignView(r.id(),r.requestId(),r.ecuId(),r.lotNo(),r.dtcCode(),r.reason(),r.requestedBy(),r.targetCount(),instant(r.createdAt()),
            new DeliveryView(r.outboxId(),r.eventId(),r.status(),r.attemptCount(),r.cycleNo(),r.lastOutcome(),r.lastHttpStatus(),instant(r.nextAttemptAt()),instant(r.sentAt()),r.deadReason()),vehicles);
    }
}
