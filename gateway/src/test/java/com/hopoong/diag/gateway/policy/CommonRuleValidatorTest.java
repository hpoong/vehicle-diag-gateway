package com.hopoong.diag.gateway.policy;
import com.hopoong.diag.gateway.ingest.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommonRuleValidatorTest {
    private final CommonRuleValidator validator = new CommonRuleValidator();
    private final Instant now = Instant.parse("2026-11-07T00:00:00Z");
    private DiagnosticResultCommand cmd(Instant time, List<EcuCommand> ecus) {
        return new DiagnosticResultCommand("d", "e", "X", "1HGCM82633A004352", "s", time.atOffset(ZoneOffset.UTC), ecus);
    }
    @Test void timeBoundaryAndEmptyEcus() {
        var ecu = List.of(new EcuCommand("A", null, List.of(), 0));
        assertThat(validator.validate(cmd(now.plusSeconds(300), ecu), now)).isEmpty();
        assertThat(validator.validate(cmd(now.minusSeconds(2592000), ecu), now)).isEmpty();
        assertThat(validator.validate(cmd(now.plusSeconds(300).plusMillis(1), ecu), now))
                .extracting(Violation::rule).containsExactly("COMMON.OCCURRED_AT_FUTURE");
        assertThat(validator.validate(cmd(now, List.of()), now)).extracting(Violation::rule)
                .containsExactly("COMMON.ECUS_REQUIRED");
    }
    @Test void collectsViolationsUsingRequestPositions() {
        var result = validator.validate(cmd(now.plusSeconds(301), List.of(
                new EcuCommand("A", null, List.of(), 2),
                new EcuCommand("A", null, List.of("P0001", "P0001"), 0))), now);
        assertThat(result).extracting(Violation::field)
                .containsExactly("occurredAt", "ecus[0].ecuId", "ecus[0].dtcCodes");
    }
}
