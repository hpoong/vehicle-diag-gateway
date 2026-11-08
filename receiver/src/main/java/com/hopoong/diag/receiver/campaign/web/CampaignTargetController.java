package com.hopoong.diag.receiver.campaign.web;
import com.hopoong.diag.receiver.campaign.application.CampaignTargetService;
import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
@RestController
@RequestMapping("/api/v1/campaign-targets")
@RequiredArgsConstructor
public class CampaignTargetController {
    private final CampaignTargetService service;
    @PostMapping
    public ApiResponse<CampaignTargetResponse> apply(@Valid @RequestBody CampaignTargetRequest request) {
        MDC.put("eventId",request.eventId());
        try {
            var result=service.apply(request);
            return ApiResponse.success(new CampaignTargetResponse(request.eventId(),result.outcome().name(),result.caseCount()));
        } finally {MDC.remove("eventId");}
    }
}
