package com.hopoong.diag.gateway.ingest.application;
import com.hopoong.diag.gateway.delivery.persistence.OutboxMapper;
import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import com.hopoong.diag.gateway.ingest.persistence.DiagnosticResultMapper;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultResponse;

@Service
public class DiagnosticResultQueryService {
    private final DiagnosticResultMapper mapper;
    private final OutboxMapper outbox;
    public DiagnosticResultQueryService(DiagnosticResultMapper mapper, OutboxMapper outbox) {
        this.mapper = mapper; this.outbox = outbox;
    }
    public DiagnosticResultResponse get(long id) {
        var result = mapper.findById(id).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        var delivery = outbox.findByResultId(id).orElseThrow(() -> new IllegalStateException("Result outbox missing"));
        var ecus = mapper.findObservations(id).stream().map(row -> new DiagnosticResultResponse.EcuView(
                row.getEcuId(), row.getSwVersion(), Arrays.asList(row.getDtcCodes()))).toList();
        return new DiagnosticResultResponse(result.id(), result.deviceId(), result.eventId(), result.brand(), result.vin(),
                result.sessionId(), result.occurredAt().toInstant(), result.receivedAt().toInstant(), result.policyCode(),
                ecus, new DiagnosticResultResponse.DeliveryView(delivery.eventId(), delivery.status(), delivery.attemptCount(),
                delivery.lastOutcome(), delivery.sentAt() == null ? null : delivery.sentAt().toInstant()));
    }
}
