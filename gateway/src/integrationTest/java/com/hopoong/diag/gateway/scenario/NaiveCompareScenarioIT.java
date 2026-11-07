package com.hopoong.diag.gateway.scenario;

import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.receiver.fault.FaultMode;
import com.hopoong.diag.gateway.delivery.application.RecordResult;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class NaiveCompareScenarioIT extends ScenarioITSupport {
    static ReceiverRunner RECEIVER;
    @BeforeAll static void startReceiver() { RECEIVER = ReceiverRunner.start(true); }
    @AfterAll static void stopReceiver() { if (RECEIVER != null) RECEIVER.close(); }
    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry r) { r.add("gateway.delivery.receiver-url", () -> RECEIVER.url()); }
    @Override protected ReceiverRunner receiver() { return RECEIVER; }

    @Test void s12_responseLossDuplicatesEffectsWithoutIdempotency() {
        fixture.createPending(20); fault(FaultMode.DROP_AFTER_COMMIT);
        assertThat(delivery.runOnce().retry()).isEqualTo(20);
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM delivery_attempt WHERE outcome='UNCERTAIN' AND error_code='READ_TIMEOUT'",Integer.class)).isEqualTo(20);
        assertThat(receiver().jdbc().queryForObject("SELECT count(*) FROM quality_case_naive",Integer.class)).isEqualTo(20);
        fault(FaultMode.NONE); pullNextAttempt(); assertThat(delivery.runOnce().sent()).isEqualTo(20);
        var report=report(ReconcileAssertions.Mode.NAIVE);
        org.slf4j.LoggerFactory.getLogger(getClass()).info(report.line("S12"));
        report.assertAllPass(); assertThat(report.dup1()).isPositive();
        assertThat(report.received()).isZero(); assertThat(report.dead()).isZero();
        for(String id:List.of("I3","I4","I5")) assertThat(report.verdicts().get(id).status()).isEqualTo(ReconcileReport.Status.SKIP);
    }
}
