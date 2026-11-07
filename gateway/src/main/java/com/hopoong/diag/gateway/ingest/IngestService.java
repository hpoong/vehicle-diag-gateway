package com.hopoong.diag.gateway.ingest;

import com.hopoong.diag.gateway.common.error.GatewayErrorCode;
import com.hopoong.diag.gateway.policy.PolicyRegistry;
import com.hopoong.diag.websupport.error.ApiFieldError;
import com.hopoong.diag.websupport.error.BusinessException;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private final DiagnosticResultNormalizer normalizer;
    private final DiagnosticResultMapper mapper;
    private final PolicyRegistry policyRegistry;

    public IngestService(DiagnosticResultNormalizer normalizer, DiagnosticResultMapper mapper,
            PolicyRegistry policyRegistry) {
        this.normalizer = normalizer;
        this.mapper = mapper;
        this.policyRegistry = policyRegistry;
    }

    public IngestResult ingest(DiagnosticResultRequest request) {
        NormalizedRequest normalized = normalizer.normalize(request);
        var existing = mapper.findByKey(request.deviceId(), request.eventId());
        if (existing.isPresent()) {
            return judgeExisting(existing.get(), normalized.command(), normalized.payloadHash());
        }
        var policy = policyRegistry.resolve(normalized.command().brand());
        var violations = policy.validate(normalized.command());
        if (!violations.isEmpty()) {
            var errors = violations.stream()
                    .map(violation -> new ApiFieldError(violation.field(), violation.rule(), violation.message()))
                    .toList();
            throw new BusinessException(GatewayErrorCode.POLICY_VIOLATION, errors, Map.of("policyCode", policy.code()));
        }
        throw new UnsupportedOperationException("Ingest processing is not implemented yet");
    }

    public IngestResult judgeExisting(ExistingResult existing, DiagnosticResultCommand command, String payloadHash) {
        if (existing.deliveryEventId() == null) {
            log.error("INGEST_OUTBOX_MISSING resultId={}", existing.id());
        }
        if (!existing.payloadHash().equals(payloadHash)) {
            throw new BusinessException(GatewayErrorCode.EVENT_CONFLICT);
        }
        return new IngestResult(IngestResult.Outcome.DUPLICATE, existing.id(), command.deviceId(),
                command.eventId(), existing.policyCode(), existing.receivedAt().toInstant(),
                existing.deliveryEventId() == null ? null : UUID.fromString(existing.deliveryEventId()),
                existing.deliveryStatus());
    }
}
