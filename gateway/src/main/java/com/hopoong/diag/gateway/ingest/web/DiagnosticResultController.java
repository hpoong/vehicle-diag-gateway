package com.hopoong.diag.gateway.ingest.web;

import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.hopoong.diag.gateway.ingest.application.DiagnosticResultQueryService;
import com.hopoong.diag.gateway.ingest.application.IngestResult;
import com.hopoong.diag.gateway.ingest.application.IngestService;

@RestController
@RequestMapping("/api/v1/diagnostic-results")
public class DiagnosticResultController {
    private final IngestService ingestService;
    private final DiagnosticResultQueryService queryService;
    public DiagnosticResultController(IngestService ingestService, DiagnosticResultQueryService queryService) {
        this.ingestService = ingestService; this.queryService = queryService;
    }
    @PostMapping
    public ResponseEntity<ApiResponse<IngestResponse>> ingest(@Valid @RequestBody DiagnosticResultRequest request) {
        MDC.put("deviceId", request.deviceId());
        MDC.put("eventId", request.eventId());
        try {
            var result = ingestService.ingest(request);
            return ResponseEntity.status(result.outcome() == IngestResult.Outcome.ACCEPTED ? 202 : 200)
                    .body(ApiResponse.success(IngestResponse.from(result)));
        } finally {
            MDC.remove("deviceId"); MDC.remove("eventId"); MDC.remove("resultId");
        }
    }
    @GetMapping("/{resultId}")
    public ResponseEntity<ApiResponse<DiagnosticResultResponse>> get(@PathVariable long resultId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.get(resultId)));
    }
}
