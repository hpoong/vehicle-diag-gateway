package com.hopoong.diag.gateway.policy;

import com.hopoong.diag.gateway.ingest.DiagnosticResultCommand;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class StandardPolicy implements ResultValidationPolicy {
    @Override public String code() { return "STANDARD-v1"; }
    @Override public Set<String> brands() { return Set.of(); }
    @Override public List<Violation> validate(DiagnosticResultCommand command) { return List.of(); }
}
