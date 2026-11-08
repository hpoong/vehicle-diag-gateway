package com.hopoong.diag.gateway.campaign.web;
import com.hopoong.diag.gateway.campaign.application.CampaignService;
import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {
    private final CampaignService service;
    @PostMapping
    public ResponseEntity<ApiResponse<CampaignCreateResponse>> create(@Valid @RequestBody CampaignCreateRequest request) {
        var result=service.create(request);
        return ResponseEntity.status("CREATED".equals(result.result())?202:200).body(ApiResponse.success(result));
    }
    @GetMapping("/{campaignId}")
    public ApiResponse<CampaignView> get(@PathVariable long campaignId,@RequestParam(defaultValue="false") boolean includeVehicles) {
        return ApiResponse.success(service.get(campaignId,includeVehicles));
    }
}
