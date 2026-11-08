package com.hopoong.diag.receiver.campaign.application;
import com.hopoong.diag.receiver.campaign.persistence.CampaignTargetMapper;
import com.hopoong.diag.receiver.quality.application.ApplyResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
@Component
@RequiredArgsConstructor
public class CampaignTargetWriter {
    private final CampaignTargetMapper mapper;
    @Transactional(rollbackFor=Exception.class)
    public ApplyResult apply(CampaignTargetCommand cmd,String hash) {
        var inserted=mapper.insertReceived(cmd.eventId(),hash,cmd.campaignId(),cmd.ecuId(),cmd.lotNo(),cmd.vehicles().size());
        if (inserted.isPresent()) {
            if (mapper.insertVehicles(cmd.eventId(),cmd.vehicles())!=cmd.vehicles().size()) throw new IllegalStateException("Campaign vehicle count mismatch");
            return new ApplyResult(ApplyResult.Outcome.APPLIED,cmd.vehicles().size());
        }
        var stored=mapper.findReceived(cmd.eventId()).orElseThrow(() -> new IllegalStateException("Received campaign disappeared"));
        return new ApplyResult(stored.payloadHash().equals(hash)?ApplyResult.Outcome.DUPLICATE:ApplyResult.Outcome.CONFLICT,stored.vehicleCount());
    }
}
