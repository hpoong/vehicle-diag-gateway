package com.hopoong.diag.receiver.quality.application;

import com.hopoong.diag.receiver.quality.persistence.QualityEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class QualityEventWriter {
    private final QualityEventMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public ApplyResult apply(QualityEventCommand cmd, String payloadHash) {
        var inserted = mapper.insertReceived(cmd.eventId(), payloadHash, cmd.sourceResultId(), cmd.vin(), cmd.caseCount());
        if (inserted.isPresent()) {
            if (!cmd.cases().isEmpty()) {
                verifyCount(mapper.insertCases(cmd.eventId(), cmd.vin(), cmd.occurredAt(), cmd.cases()), cmd.caseCount());
            }
            return new ApplyResult(ApplyResult.Outcome.APPLIED, cmd.caseCount());
        }
        var stored = mapper.findReceived(cmd.eventId())
                .orElseThrow(() -> new IllegalStateException("Received event disappeared"));
        return new ApplyResult(stored.payloadHash().equals(payloadHash)
                ? ApplyResult.Outcome.DUPLICATE : ApplyResult.Outcome.CONFLICT, stored.caseCount());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApplyResult applyNaive(QualityEventCommand cmd) {
        if (!cmd.cases().isEmpty()) {
            verifyCount(mapper.insertNaiveCases(cmd.eventId(), cmd.cases()), cmd.caseCount());
        }
        return new ApplyResult(ApplyResult.Outcome.APPLIED, cmd.caseCount());
    }

    private void verifyCount(int actual, int expected) {
        if (actual != expected) throw new IllegalStateException("Quality case count mismatch");
    }
}
