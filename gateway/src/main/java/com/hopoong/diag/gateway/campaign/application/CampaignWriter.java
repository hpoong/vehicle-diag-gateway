package com.hopoong.diag.gateway.campaign.application;
import com.hopoong.diag.gateway.campaign.persistence.CampaignMapper;
import com.hopoong.diag.gateway.trace.persistence.TraceMapper;
import com.hopoong.diag.gateway.delivery.persistence.OutboxMapper;
import com.hopoong.diag.websupport.error.BusinessException;
import static com.hopoong.diag.gateway.common.error.GatewayErrorCode.*;
import static com.hopoong.diag.gateway.campaign.application.CampaignLimits.MAX_TARGETS;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
@Component
@RequiredArgsConstructor
@Slf4j
public class CampaignWriter {
    private final CampaignMapper mapper;
    private final TraceMapper traceMapper;
    private final OutboxMapper outbox;
    private final CampaignPayloadFactory payloadFactory;
    @Transactional(rollbackFor=Exception.class)
    public CreateOutcome create(CampaignCommand cmd,String hash) {
        var existing=mapper.findByRequestId(cmd.requestId());
        if (existing.isPresent()) return CreateOutcome.existing(existing.get());
        long count=traceMapper.countCurrentVehicles(cmd.ecuId(),cmd.lotNo());
        if (count==0) {log.info("CAMPAIGN_REJECTED(no-campaign-target) targetCount=0");throw new BusinessException(NO_CAMPAIGN_TARGET);}
        if (count>MAX_TARGETS) {log.info("CAMPAIGN_REJECTED(campaign-too-large) targetCount={}",count);throw new BusinessException(CAMPAIGN_TOO_LARGE,List.of(),Map.of("targetCount",count,"maxTargets",MAX_TARGETS));}
        var inserted=mapper.insertCampaign(cmd,hash,(int)count);
        if (inserted.isEmpty()) return CreateOutcome.existing(mapper.findByRequestId(cmd.requestId()).orElseThrow(() -> new IllegalStateException("Concurrent campaign missing after conflict")));
        var campaign=inserted.get();
        var vehicles=mapper.insertSnapshot(campaign.id(),cmd.ecuId(),cmd.lotNo());
        if (vehicles.size()!=count) {log.info("CAMPAIGN_REJECTED(campaign-target-changed) targetCount={}",count);throw new BusinessException(CAMPAIGN_TARGET_CHANGED);}
        var draft=payloadFactory.create(cmd,campaign.id(),campaign.createdAt(),vehicles);
        outbox.insertCampaign(campaign.id(),draft.eventId().toString(),draft.payloadJson());
        return CreateOutcome.created(campaign.id(),(int)count,draft.eventId().toString());
    }
}
