package com.hopoong.diag.gateway.scenario;

import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import com.hopoong.diag.receiver.fault.FaultMode;
import java.util.*;

class ReconcileAssertionsIT extends ScenarioITSupport {
    static ReceiverRunner RECEIVER;
    @BeforeAll static void startReceiver() { RECEIVER = ReceiverRunner.start(false); }
    @AfterAll static void stopReceiver() { if (RECEIVER != null) RECEIVER.close(); }
    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry r) { r.add("gateway.delivery.receiver-url", () -> RECEIVER.url()); }
    @Override protected ReceiverRunner receiver() { return RECEIVER; }

    private ReconcileReport check() { return report(ReconcileAssertions.Mode.STANDARD); }
    private void delivered() { fixture.createPending(3); drain(3); }
    @Test void normalDeliveryPassesAllChecks() {
        delivered(); var r=check(); r.assertAllPass(); assertThat(r.dup1()).isZero();
        assertThat(r.attemptOutcomes()).containsEntry("SUCCESS", 3);
        assertThat(r.line("TEST")).doesNotContain(com.hopoong.diag.testsupport.OutboxFixture.VIN);
    }
    @Test void missingReceivedEventFailsWithItsId() {
        delivered(); String id=receiver().jdbc().queryForObject("SELECT event_id::text FROM received_event LIMIT 1", String.class);
        receiver().jdbc().update("DELETE FROM quality_case WHERE event_id=?::uuid", id);
        receiver().jdbc().update("DELETE FROM received_event WHERE event_id=?::uuid", id);
        assertThat(check().verdicts().get("I3").status()).isEqualTo(ReconcileReport.Status.FAIL);
        assertThat(check().verdicts().get("I3").detail()).contains(id);
    }
    @Test void deadReceivedEventNeedsExplicitAllowance() {
        delivered();
        String id=gatewayJdbc.queryForObject("SELECT event_id::text FROM outbox_event LIMIT 1", String.class);
        gatewayJdbc.update("UPDATE outbox_event SET status='DEAD', dead_reason='MAX_ATTEMPTS', dead_at=now(), sent_at=NULL WHERE event_id=?::uuid", id);
        assertThat(check().verdicts().get("I4").status()).isEqualTo(ReconcileReport.Status.FAIL);
        var r=new ReconcileAssertions(gatewayJdbc,receiver().jdbc()).check(ReconcileAssertions.Mode.STANDARD, Set.of(id));
        r.assertAllPass();
    }
    @Test void pendingSkipsTerminalSetComparison() {
        fixture.createPending(1); assertThat(check().verdicts().get("I4").status()).isEqualTo(ReconcileReport.Status.SKIP);
    }
    @Test void wrongCaseCountFails() {
        delivered(); receiver().jdbc().update("UPDATE received_event SET case_count=case_count+1");
        assertThat(check().verdicts().get("I5").status()).isEqualTo(ReconcileReport.Status.FAIL);
    }
    @Test void wrongAndMissingLatestFail() {
        delivered(); gatewayJdbc.update("UPDATE vehicle_ecu_latest SET occurred_at=occurred_at-interval '1 hour'");
        assertThat(check().verdicts().get("I6").detail()).contains("wrong_latest=1");
        gatewayJdbc.update("DELETE FROM vehicle_ecu_latest");
        assertThat(check().verdicts().get("I6").detail()).contains("missing_latest=1");
    }
    @Test void naiveCountsDuplicateGroupsAndSkipsReceiverChecks() {
        fixture.createPending(1);
        receiver().jdbc().update("INSERT INTO quality_case_naive(event_id, ecu_id, dtc_code) VALUES (?::uuid,'BMS','P0A80'),(?::uuid,'BMS','P0A80')",
                "00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000001");
        var r=report(ReconcileAssertions.Mode.NAIVE); r.assertAllPass(); assertThat(r.dup1()).isEqualTo(1);
        for(String id:List.of("I3","I4","I5")) assertThat(r.verdicts().get(id).status()).isEqualTo(ReconcileReport.Status.SKIP);
    }
    @Test void missingOutboxFailsI1() {
        fixture.createPending(1); gatewayJdbc.update("DELETE FROM outbox_event");
        assertThat(check().verdicts().get("I1").status()).isEqualTo(ReconcileReport.Status.FAIL);
    }
}
