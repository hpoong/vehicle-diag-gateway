package com.hopoong.diag.gateway.ingest.application;

import com.hopoong.diag.gateway.common.error.GatewayErrorCode;
import com.hopoong.diag.gateway.policy.CommonRuleValidator;
import com.hopoong.diag.gateway.policy.PolicyRegistry;
import com.hopoong.diag.websupport.error.ApiFieldError;
import com.hopoong.diag.websupport.error.BusinessException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;
import com.hopoong.diag.gateway.ingest.persistence.DiagnosticResultMapper;
import com.hopoong.diag.gateway.ingest.persistence.ExistingResult;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultRequest;

@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private final DiagnosticResultNormalizer normalizer;
    private final DiagnosticResultMapper mapper;
    private final PolicyRegistry policyRegistry;
    private final CommonRuleValidator commonRules;
    private final IngestWriter writer;

    public IngestService(DiagnosticResultNormalizer normalizer, DiagnosticResultMapper mapper,
            PolicyRegistry policyRegistry, CommonRuleValidator commonRules, IngestWriter writer) {
        this.commonRules = commonRules;
        this.writer = writer;
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
        var commonViolations = commonRules.validate(normalized.command(), Instant.now());
        if (!commonViolations.isEmpty()) {
            log.info("INGEST_REJECTED(common-rule-violation) violations={}", commonViolations.size());
            throw new BusinessException(GatewayErrorCode.COMMON_RULE_VIOLATION, commonViolations.stream()
                    .map(v -> new ApiFieldError(v.field(), v.rule(), v.message())).toList());
        }
        var policy = policyRegistry.resolve(normalized.command().brand());
        var violations = policy.validate(normalized.command());
        if (!violations.isEmpty()) {
            log.info("INGEST_REJECTED(policy-violation) policyCode={} violations={}", policy.code(), violations.size());
            var errors = violations.stream()
                    .map(violation -> new ApiFieldError(violation.field(), violation.rule(), violation.message()))
                    .toList();
            throw new BusinessException(GatewayErrorCode.POLICY_VIOLATION, errors, Map.of("policyCode", policy.code()));
        }
        var saved = writer.save(normalized.command(), normalized.payloadHash(), policy.code(), normalized.rawPayload());
        if (saved.isEmpty()) {
            return judgeExisting(mapper.findByKey(request.deviceId(), request.eventId()).orElseThrow(
                    () -> new IllegalStateException("Concurrent result missing after insert conflict")),
                    normalized.command(), normalized.payloadHash());
        }
        var result = saved.get();
        MDC.put("resultId", Long.toString(result.resultId()));
        log.info("INGEST_ACCEPTED policyCode={}", policy.code());
        return new IngestResult(IngestResult.Outcome.ACCEPTED, result.resultId(), request.deviceId(), request.eventId(),
                policy.code(), result.receivedAt(), result.deliveryEventId(), "PENDING");
    }

    public IngestResult judgeExisting(ExistingResult existing, DiagnosticResultCommand command, String payloadHash) {
        MDC.put("resultId", Long.toString(existing.id()));
        if (existing.deliveryEventId() == null) {
            log.error("INGEST_OUTBOX_MISSING resultId={}", existing.id());
        }
        if (!existing.payloadHash().equals(payloadHash)) {
            log.info("INGEST_CONFLICT");
            throw new BusinessException(GatewayErrorCode.EVENT_CONFLICT);
        }
        log.info("INGEST_DUPLICATE");
        return new IngestResult(IngestResult.Outcome.DUPLICATE, existing.id(), command.deviceId(),
                command.eventId(), existing.policyCode(), existing.receivedAt().toInstant(),
                existing.deliveryEventId() == null ? null : UUID.fromString(existing.deliveryEventId()),
                existing.deliveryStatus());
    }
}
