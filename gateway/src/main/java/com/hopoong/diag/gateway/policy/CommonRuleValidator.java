package com.hopoong.diag.gateway.policy;

import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CommonRuleValidator {
    public List<Violation> validate(DiagnosticResultCommand command, Instant now) {
        List<Violation> violations = new ArrayList<>();
        if (command.ecus().isEmpty()) {
            violations.add(new Violation("ecus", "COMMON.ECUS_REQUIRED", "ECU가 하나 이상 필요합니다"));
        }
        if (command.occurredAt().toInstant().isAfter(now.plusSeconds(300))) {
            violations.add(new Violation("occurredAt", "COMMON.OCCURRED_AT_FUTURE", "진단 시각이 허용 범위를 초과합니다"));
        }
        var ids = new HashSet<String>();
        for (var ecu : command.ecus()) {
            String field = "ecus[" + ecu.requestIndex() + "]";
            if (!ids.add(ecu.ecuId())) {
                violations.add(new Violation(field + ".ecuId", "COMMON.ECU_DUPLICATED", "ECU ID가 중복됩니다"));
            }
            if (new HashSet<>(ecu.dtcCodes()).size() != ecu.dtcCodes().size()) {
                violations.add(new Violation(field + ".dtcCodes", "COMMON.DTC_DUPLICATED", "고장코드가 중복됩니다"));
            }
        }
        return List.copyOf(violations);
    }
}
