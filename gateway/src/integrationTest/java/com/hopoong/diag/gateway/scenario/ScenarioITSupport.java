package com.hopoong.diag.gateway.scenario;

import com.hopoong.diag.gateway.delivery.application.*;
import com.hopoong.diag.gateway.delivery.client.ReceiverClient;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.testsupport.OutboxFixture;
import com.hopoong.diag.receiver.fault.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"gateway.delivery.enabled=false", "gateway.delivery.batch-size=20",
        "gateway.delivery.max-attempts=3", "gateway.delivery.backoff.base=0s",
        "gateway.delivery.connect-timeout=500ms", "gateway.delivery.read-timeout=500ms"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ScenarioITSupport {
    @Autowired protected JdbcTemplate gatewayJdbc;
    @Autowired protected IngestService ingest;
    @Autowired protected DeliveryService delivery;
    @Autowired protected OutboxClaimer claimer;
    @Autowired protected DeliveryRecorder recorder;
    @Autowired protected ReceiverClient client;
    @Autowired protected OutcomeClassifier classifier;
    @Autowired protected MockMvc mvc;
    protected OutboxFixture fixture;
    protected abstract ReceiverRunner receiver();
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", ScenarioPostgres::gatewayJdbcUrl);
        registry.add("spring.datasource.username", ScenarioPostgres::username);
        registry.add("spring.datasource.password", ScenarioPostgres::password);
    }
    @BeforeEach void resetAll() {
        fixture = new OutboxFixture(ingest, gatewayJdbc);
        fixture.reset(); receiver().reset();
    }
    protected void fault(FaultMode mode) { fault(mode, 1500); }
    protected void fault(FaultMode mode, long holdMs) { receiver().faults().set(new FaultConfig(mode, holdMs)); }
    protected void pullNextAttempt() { gatewayJdbc.update("UPDATE outbox_event SET next_attempt_at=now() WHERE status='PENDING'"); }
    protected void expireLeases() { gatewayJdbc.update("UPDATE outbox_event SET locked_until=now()-interval '1 second' WHERE claim_token IS NOT NULL"); }
    protected void drain(int maxRounds) {
        for (int i = 0; i < maxRounds; i++) {
            pullNextAttempt();
            var summary = delivery.runOnce();
            assertThat(summary.failed()).isZero();
            if (summary.claimed() == 0) return;
        }
        throw new AssertionError("drain 최대 횟수 초과: " + maxRounds);
    }
    protected void assertStandard(String scenario, int expected) {
        var report = report(ReconcileAssertions.Mode.STANDARD);
        org.slf4j.LoggerFactory.getLogger(getClass()).info(report.line(scenario));
        report.assertAllPass();
        assertThat(report.results()).isEqualTo(expected);
        assertThat(report.received()).isEqualTo(expected);
        assertThat(report.sent()).isEqualTo(expected);
        assertThat(report.pending()).isZero();
        assertThat(report.dead()).isZero();
        assertThat(report.dup1()).isZero();
    }
    protected ReconcileReport report(ReconcileAssertions.Mode mode) {
        return new ReconcileAssertions(gatewayJdbc, receiver().jdbc()).check(mode, java.util.Set.of());
    }
}
