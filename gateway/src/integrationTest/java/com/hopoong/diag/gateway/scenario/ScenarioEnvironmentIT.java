package com.hopoong.diag.gateway.scenario;

import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import com.hopoong.diag.receiver.fault.FaultMode;
import java.util.*;

class ScenarioEnvironmentIT extends ScenarioITSupport {
    static ReceiverRunner RECEIVER;
    @BeforeAll static void startReceiver() { RECEIVER = ReceiverRunner.start(false); }
    @AfterAll static void stopReceiver() { if (RECEIVER != null) RECEIVER.close(); }
    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry r) { r.add("gateway.delivery.receiver-url", () -> RECEIVER.url()); }
    @Override protected ReceiverRunner receiver() { return RECEIVER; }

    @Test void databasesAndConfigurationAreIsolated() {
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(3);
        assertThat(receiver().jdbc().queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(1);
        assertThat(receiver().jdbc().queryForObject("SELECT current_database()", String.class)).isEqualTo("receiver");
        assertThat(receiver().jdbc().queryForObject("SELECT to_regclass('outbox_event')::text", String.class)).isNull();
        assertThat(receiver().url()).doesNotEndWith(":8081");
    }
    @Test void deliveryIsManualAndReceiverRecovers() {
        fixture.createPending(1);
        assertThat(gatewayJdbc.queryForObject("SELECT status FROM outbox_event", String.class)).isEqualTo("PENDING");
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM delivery_attempt", Integer.class)).isZero();
        fault(FaultMode.DOWN);
        assertThat(delivery.runOnce().retry()).isEqualTo(1);
        assertThat(gatewayJdbc.queryForObject("SELECT http_status FROM delivery_attempt", Integer.class)).isEqualTo(503);
        fault(FaultMode.NONE); pullNextAttempt();
        assertThat(delivery.runOnce().sent()).isEqualTo(1);
        report(ReconcileAssertions.Mode.STANDARD).assertAllPass();
    }
    @Test void timeHelpersMakeRowsDueAndExpireClaims() {
        fixture.createPending(1);
        gatewayJdbc.update("UPDATE outbox_event SET next_attempt_at=now()+interval '1 hour'");
        pullNextAttempt();
        assertThat(gatewayJdbc.queryForObject("SELECT next_attempt_at <= now() FROM outbox_event", Boolean.class)).isTrue();
        claimer.claim(1); expireLeases();
        assertThat(gatewayJdbc.queryForObject("SELECT locked_until < now() FROM outbox_event", Boolean.class)).isTrue();
    }
    @Test void naiveReceiverWritesOnlyComparisonTable() {
        try (var naive = ReceiverRunner.start(true)) {
            var id = fixture.createPending(1).get(0);
            var payload = gatewayJdbc.queryForObject("SELECT payload::text FROM outbox_event WHERE id=?", String.class, id);
            var eventId = gatewayJdbc.queryForObject("SELECT event_id::text FROM outbox_event WHERE id=?", String.class, id);
            var http = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(naive.url()+"/api/v1/quality-events"))
                    .header("Content-Type", "application/json").header("X-Event-Id", eventId)
                    .header("X-Claim-Token", UUID.randomUUID().toString())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload)).build();
            assertThat(http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isBetween(200,299);
            assertThat(naive.jdbc().queryForObject("SELECT count(*) FROM quality_case_naive", Integer.class)).isPositive();
            assertThat(naive.jdbc().queryForObject("SELECT count(*) FROM received_event", Integer.class)).isZero();
        } catch (Exception e) { throw new AssertionError(e); }
    }
}
