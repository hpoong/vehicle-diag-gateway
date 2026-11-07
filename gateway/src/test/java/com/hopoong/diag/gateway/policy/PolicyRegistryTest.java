package com.hopoong.diag.gateway.policy;

import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;
import com.hopoong.diag.gateway.ingest.domain.EcuCommand;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyRegistryTest {
    @Test
    void resolvesBrandAndFallsBackToStandard() {
        var registry = new PolicyRegistry(List.of(new StandardPolicy(), new BrandAPolicy()));
        assertThat(registry.resolve("BRAND_A").code()).isEqualTo("BRAND_A-v1");
        assertThat(registry.resolve("ZETA").code()).isEqualTo("STANDARD-v1");
    }

    @Test
    void brandPolicyReportsAllMissingVersionsUsingOriginalIndexes() {
        var command = new DiagnosticResultCommand("device", "event", "BRAND_A", "vin", "session",
                OffsetDateTime.parse("2026-11-06T00:00:00Z"), List.of(
                new EcuCommand("ABS", null, List.of(), 2),
                new EcuCommand("BMS", "1", List.of(), 1),
                new EcuCommand("VCU", null, List.of(), 0)));
        var violations = new BrandAPolicy().validate(command);
        assertThat(violations).extracting(Violation::field)
                .containsExactlyInAnyOrder("ecus[0].swVersion", "ecus[2].swVersion");
        assertThat(violations).extracting(Violation::rule)
                .containsOnly("BRAND_A.SW_VERSION_REQUIRED");
        assertThat(new StandardPolicy().validate(command)).isEmpty();
    }

    @Test
    void rejectsDuplicateBrandAndMissingStandard() {
        ResultValidationPolicy one = policy("ONE", "X");
        ResultValidationPolicy two = policy("TWO", "X");
        assertThatThrownBy(() -> new PolicyRegistry(List.of(new StandardPolicy(), one, two)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PolicyRegistry(List.of(one)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PolicyRegistry(List.of(new StandardPolicy(), new StandardPolicy())))
                .isInstanceOf(IllegalStateException.class);
    }

    private ResultValidationPolicy policy(String code, String brand) {
        return new ResultValidationPolicy() {
            @Override public String code() { return code; }
            @Override public Set<String> brands() { return Set.of(brand); }
            @Override public List<Violation> validate(DiagnosticResultCommand command) { return List.of(); }
        };
    }
}
