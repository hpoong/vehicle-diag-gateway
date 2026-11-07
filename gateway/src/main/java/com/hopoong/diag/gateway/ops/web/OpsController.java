package com.hopoong.diag.gateway.ops.web;

import com.hopoong.diag.gateway.ops.application.*;
import com.hopoong.diag.websupport.response.ApiResponse;
import com.hopoong.diag.websupport.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class OpsController {
    private final OpsService service;
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<OpsSummaryResponse>> summary() {
        return ResponseEntity.ok(ApiResponse.success(service.getSummary()));
    }
    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<OpsEventView>>> events(@RequestParam OutboxStatus status,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return ResponseEntity.ok(ApiResponse.success(service.getEvents(status, limit)));
    }
    @GetMapping("/events/{outboxId}/attempts")
    public ResponseEntity<ApiResponse<List<OpsAttemptView>>> attempts(@PathVariable long outboxId) {
        return ResponseEntity.ok(ApiResponse.success(service.getAttempts(outboxId)));
    }
    @PostMapping("/events/{outboxId}/requeue")
    public ResponseEntity<ApiResponse<OpsEventView>> requeue(@PathVariable long outboxId,
            @RequestHeader("X-Operator") @NotBlank @Size(max = 64) String operator,
            @Valid @RequestBody RequeueRequest body, HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.success(service.requeue(outboxId, operator, body.reason(),
                (String) http.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }
}
