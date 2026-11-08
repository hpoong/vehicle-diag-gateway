package com.hopoong.diag.receiver.campaign.persistence;
import com.hopoong.diag.receiver.campaign.web.CampaignVehicleRequest;
import org.apache.ibatis.annotations.*;
import java.util.*;
@Mapper
public interface CampaignTargetMapper {
    Optional<String> insertReceived(@Param("eventId") String eventId,@Param("payloadHash") String payloadHash,@Param("campaignId") long campaignId,@Param("ecuId") String ecuId,@Param("lotNo") String lotNo,@Param("vehicleCount") int vehicleCount);
    Optional<ReceivedCampaignRow> findReceived(@Param("eventId") String eventId);
    int insertVehicles(@Param("eventId") String eventId,@Param("vehicles") List<CampaignVehicleRequest> vehicles);
}
