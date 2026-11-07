package com.hopoong.diag.receiver.quality.application;

import java.time.OffsetDateTime;
import java.util.List;

public record QualityEventCommand(String eventId, long sourceResultId, String vin,
        OffsetDateTime occurredAt, List<CaseRow> cases) {
    public QualityEventCommand {
        cases = List.copyOf(cases);
    }

    public int caseCount() { return cases.size(); }
}
