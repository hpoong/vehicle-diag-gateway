package com.hopoong.diag.gateway.ingest;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/diagnostic-results")
public class DiagnosticResultController {
    private final IngestService ingestService;

    public DiagnosticResultController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public void ingest(@Valid @RequestBody DiagnosticResultRequest request) {
        ingestService.ingest(request);
    }
}
