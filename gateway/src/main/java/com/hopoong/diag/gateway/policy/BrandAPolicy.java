package com.hopoong.diag.gateway.policy;

import com.hopoong.diag.gateway.ingest.DiagnosticResultCommand;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class BrandAPolicy implements ResultValidationPolicy {
    @Override public String code() { return "BRAND_A-v1"; }
    @Override public Set<String> brands() { return Set.of("BRAND_A"); }

    @Override
    public List<Violation> validate(DiagnosticResultCommand command) {
        return command.ecus().stream()
                .filter(ecu -> ecu.swVersion() == null)
                .map(ecu -> new Violation("ecus[" + ecu.requestIndex() + "].swVersion",
                        "BRAND_A.SW_VERSION_REQUIRED", "BRAND_A는 모든 ECU에 swVersion이 필요합니다"))
                .toList();
    }
}
