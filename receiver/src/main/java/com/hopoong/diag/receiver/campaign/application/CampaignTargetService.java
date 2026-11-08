package com.hopoong.diag.receiver.campaign.application;
import com.hopoong.diag.receiver.campaign.web.CampaignTargetRequest;
import com.hopoong.diag.receiver.quality.application.*;
import com.hopoong.diag.receiver.fault.FaultState;
import com.hopoong.diag.receiver.common.error.ReceiverErrorCode;
import com.hopoong.diag.websupport.error.*;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignTargetService {
    private final CampaignVehicleValidator validator;
    private final PayloadHasher hasher;
    private final CampaignTargetWriter writer;
    private final FaultState faultState;
    public ApplyResult apply(CampaignTargetRequest request) {
        var errors=validator.validate(request);
        if (!errors.isEmpty()) throw new BusinessException(CommonErrorCode.INVALID_REQUEST,errors);
        var cmd=new CampaignTargetCommand(request.eventId(),request.source().campaignId(),request.campaign().ecuId(),request.campaign().lotNo(),request.vehicles());
        var result=writer.apply(cmd,hasher.hash(request));
        if (result.outcome()==ApplyResult.Outcome.CONFLICT) {
            log.warn("CAMPAIGN_TARGET_CONFLICT");throw new BusinessException(ReceiverErrorCode.EVENT_CONFLICT);
        }
        log.info("CAMPAIGN_TARGET_{} vehicleCount={}",result.outcome(),result.caseCount());
        faultState.awaitIfDropAfterCommit();
        return result;
    }
}
