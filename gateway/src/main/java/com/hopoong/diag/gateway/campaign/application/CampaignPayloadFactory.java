package com.hopoong.diag.gateway.campaign.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.common.UtcTime;
import com.hopoong.diag.gateway.campaign.persistence.SnapshotVehicle;
import com.hopoong.diag.gateway.ingest.application.OutboxDraft;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
@Component
@RequiredArgsConstructor
public class CampaignPayloadFactory {
    private final ObjectMapper json;
    public OutboxDraft create(CampaignCommand cmd,long campaignId,OffsetDateTime createdAt,List<SnapshotVehicle> vehicles) {
        var id=UUID.randomUUID();
        var payload=new LinkedHashMap<String,Object>();payload.put("eventId",id.toString());
        payload.put("source",Map.of("system","diag-gateway","campaignId",campaignId,"requestId",cmd.requestId()));
        payload.put("campaign",Map.of("ecuId",cmd.ecuId(),"lotNo",cmd.lotNo(),"dtcCode",cmd.dtcCode(),"reason",cmd.reason(),"requestedBy",cmd.requestedBy(),"createdAt",UtcTime.MILLIS.format(createdAt.toInstant())));
        payload.put("vehicles",vehicles.stream().sorted(Comparator.comparing(SnapshotVehicle::vin)).toList());
        try {return new OutboxDraft(id,json.writeValueAsString(payload));}
        catch (com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("Cannot serialize campaign",e);}
    }
}
