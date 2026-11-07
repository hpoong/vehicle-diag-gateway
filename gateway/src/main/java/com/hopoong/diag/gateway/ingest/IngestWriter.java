package com.hopoong.diag.gateway.ingest;

import com.hopoong.diag.gateway.delivery.OutboxMapper;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IngestWriter {
    private final DiagnosticResultMapper mapper;
    private final OutboxPayloadFactory factory;
    private final OutboxMapper outbox;
    public IngestWriter(DiagnosticResultMapper mapper, OutboxPayloadFactory factory, OutboxMapper outbox) {
        this.mapper = mapper; this.factory = factory; this.outbox = outbox;
    }

    @Transactional(rollbackFor = Exception.class)
    public Optional<SavedResult> save(DiagnosticResultCommand cmd, String hash, String policyCode, String rawPayload) {
        var inserted = mapper.insertResult(cmd, hash, policyCode, rawPayload);
        if (inserted.isEmpty()) return Optional.empty();
        var result = inserted.get();
        mapper.insertObservations(cmd.ecus().stream().map(ecu -> new ObservationRow(result.id(), ecu.ecuId(),
                ecu.swVersion(), ecu.dtcCodes().toArray(String[]::new))).toList());
        for (var ecu : cmd.ecus()) {
            mapper.upsertLatest(cmd.vin(), ecu.ecuId(), result.id(), cmd.occurredAt(), ecu.dtcCodes().toArray(String[]::new));
        }
        var draft = factory.create(cmd, result.id(), result.receivedAt().toInstant(), policyCode);
        outbox.insert(result.id(), draft.eventId().toString(), draft.payloadJson());
        return Optional.of(new SavedResult(result.id(), result.receivedAt().toInstant(), draft.eventId()));
    }
}
