package com.hopoong.diag.gateway.policy;

import com.hopoong.diag.gateway.ingest.DiagnosticResultCommand;
import java.util.List;
import java.util.Set;

public interface ResultValidationPolicy {
    String code();
    Set<String> brands();
    List<Violation> validate(DiagnosticResultCommand command);
}
