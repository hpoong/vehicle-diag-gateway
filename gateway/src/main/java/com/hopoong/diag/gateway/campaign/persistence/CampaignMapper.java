package com.hopoong.diag.gateway.campaign.persistence;
import com.hopoong.diag.gateway.campaign.application.CampaignCommand;
import org.apache.ibatis.annotations.*;
import java.util.*;
@Mapper
public interface CampaignMapper {
    Optional<ExistingCampaign> findByRequestId(@Param("requestId") String requestId);
    Optional<InsertedCampaign> insertCampaign(@Param("cmd") CampaignCommand cmd,@Param("hash") String hash,@Param("targetCount") int targetCount);
    List<SnapshotVehicle> insertSnapshot(@Param("campaignId") long campaignId,@Param("ecuId") String ecuId,@Param("lotNo") String lotNo);
    Optional<CampaignDetailRow> findCampaign(@Param("id") long id);
    List<SnapshotVehicle> findVehicles(@Param("id") long id);
}
