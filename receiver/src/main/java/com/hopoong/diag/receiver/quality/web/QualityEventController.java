package com.hopoong.diag.receiver.quality.web;

import com.hopoong.diag.receiver.quality.application.QualityEventService;
import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quality-events")
@RequiredArgsConstructor
public class QualityEventController {
    private final QualityEventService service;

    @PostMapping
    public ResponseEntity<ApiResponse<QualityEventResponse>> apply(@Valid @RequestBody QualityEventRequest request) {
        MDC.put("eventId", request.eventId());
        try {
            var result = service.apply(request);
            return ResponseEntity.ok(ApiResponse.success(new QualityEventResponse(
                    request.eventId(), result.outcome().name(), result.caseCount())));
        } finally {
            MDC.remove("eventId");
        }
    }
}
