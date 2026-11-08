package com.hopoong.diag.gateway.campaign.application;
import com.hopoong.diag.gateway.campaign.persistence.CampaignMapper;
import com.hopoong.diag.gateway.campaign.web.*;
import com.hopoong.diag.websupport.error.*;
import static com.hopoong.diag.gateway.common.error.GatewayErrorCode.CAMPAIGN_REQUEST_CONFLICT;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignService {
    private final CampaignWriter writer;
    private final CampaignRequestHasher hasher;
    private final CampaignMapper mapper;
    public CampaignCreateResponse create(CampaignCreateRequest req) {
        var cmd=new CampaignCommand(req.requestId(),req.ecuId(),req.lotNo(),req.dtcCode(),req.reason(),req.requestedBy());
        String hash=hasher.hash(cmd);var outcome=writer.create(cmd,hash);
        if (!outcome.created() && !hash.equals(outcome.requestHash())) {
            log.info("CAMPAIGN_REJECTED(campaign-request-conflict) campaignId={}",outcome.campaignId());throw new BusinessException(CAMPAIGN_REQUEST_CONFLICT);
        }
        if (outcome.created()) log.info("CAMPAIGN_CREATED campaignId={} targetCount={} eventId={}",outcome.campaignId(),outcome.targetCount(),outcome.eventId());
        else log.info("CAMPAIGN_DUPLICATE campaignId={}",outcome.campaignId());
        return new CampaignCreateResponse(outcome.campaignId(),req.requestId(),outcome.targetCount(),outcome.created()?"CREATED":"DUPLICATE",new CampaignCreateResponse.Delivery(outcome.eventId(),outcome.deliveryStatus()));
    }
    public CampaignView get(long id,boolean includeVehicles) {
        var row=mapper.findCampaign(id).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return CampaignView.of(row,includeVehicles?mapper.findVehicles(id):null);
    }
}
