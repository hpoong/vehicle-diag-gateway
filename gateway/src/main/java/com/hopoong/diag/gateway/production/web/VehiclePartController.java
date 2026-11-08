package com.hopoong.diag.gateway.production.web;
import com.hopoong.diag.gateway.production.application.VehiclePartImportService;
import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/production/vehicle-parts")
public class VehiclePartController {
    private final VehiclePartImportService service;
    public VehiclePartController(VehiclePartImportService service) { this.service=service; }
    @PostMapping
    public ApiResponse<VehiclePartImportResponse> importParts(@Valid @RequestBody VehiclePartImportRequest request) {
        MDC.put("batchId",request.batchId()); MDC.put("source",request.source());
        try { return ApiResponse.success(service.importParts(request)); }
        finally { MDC.remove("batchId"); MDC.remove("source"); }
    }
}
