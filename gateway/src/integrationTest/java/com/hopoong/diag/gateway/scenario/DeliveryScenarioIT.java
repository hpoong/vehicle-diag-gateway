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

class DeliveryScenarioIT extends ScenarioITSupport {
    static ReceiverRunner RECEIVER;
    @BeforeAll static void startReceiver() { RECEIVER = ReceiverRunner.start(false); }
    @AfterAll static void stopReceiver() { if (RECEIVER != null) RECEIVER.close(); }
    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry r) { r.add("gateway.delivery.receiver-url", () -> RECEIVER.url()); }
    @Override protected ReceiverRunner receiver() { return RECEIVER; }

    @Test void s04_receiverDownThenRecovers() {
        fixture.createPending(20); fault(FaultMode.DOWN);
        var failed=delivery.runOnce(); assertThat(failed.retry()).isEqualTo(20); assertThat(failed.failed()).isZero();
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM delivery_attempt WHERE outcome='RETRYABLE' AND http_status=503",Integer.class)).isEqualTo(20);
        fault(FaultMode.NONE); pullNextAttempt(); assertThat(delivery.runOnce().sent()).isEqualTo(20);
        assertStandard("S4",20);
    }
    @Test void s05_responseLostAfterCommitDoesNotDuplicateEffects() {
        fixture.createPending(20); fault(FaultMode.DROP_AFTER_COMMIT);
        assertThat(delivery.runOnce().retry()).isEqualTo(20);
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM delivery_attempt WHERE outcome='UNCERTAIN' AND error_code='READ_TIMEOUT'",Integer.class)).isEqualTo(20);
        assertThat(receiver().jdbc().queryForObject("SELECT count(*) FROM received_event",Integer.class)).isEqualTo(20);
        fault(FaultMode.NONE); pullNextAttempt(); assertThat(delivery.runOnce().sent()).isEqualTo(20);
        assertStandard("S5",20);
    }
    @Test void s07_workerStopsAfterClaimAndLeaseIsRecovered() {
        fixture.createPending(1); claimer.claim(1); expireLeases();
        assertThat(delivery.runOnce().sent()).isEqualTo(1);
        assertThat(gatewayJdbc.queryForList("SELECT outcome FROM delivery_attempt ORDER BY id",String.class)).containsExactly("ABANDONED","SUCCESS");
        assertStandard("S7",1);
    }
    @Test void s08_concurrentClaimersNeverOverlap() throws Exception {
        fixture.createPending(100); var pool=Executors.newFixedThreadPool(2); var go=new CountDownLatch(1);
        try {
            Callable<List<Long>> action=() -> {
                if(!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                var ids=new ArrayList<Long>();
                while(true) { var rows=claimer.claim(4); if(rows.isEmpty()) return ids; rows.forEach(r -> ids.add(r.id())); }
            };
            var a=pool.submit(action); var c=pool.submit(action); go.countDown();
            var ids=new ArrayList<>(a.get(30,TimeUnit.SECONDS)); ids.addAll(c.get(30,TimeUnit.SECONDS));
            assertThat(ids).hasSize(100).doesNotHaveDuplicates();
        } finally { go.countDown(); pool.shutdownNow(); }
        expireLeases(); drain(10); assertStandard("S8",100);
    }
    @Test void s09_lateSuccessCannotOverwriteNewOwner() {
        fixture.createPending(1); var t1=claimer.claim(1).get(0);
        var r1=client.send(t1.eventId(),t1.claimToken(),t1.payload());
        assertThat(receiver().jdbc().queryForObject("SELECT count(*) FROM received_event",Integer.class)).isEqualTo(1);
        expireLeases(); var t2=claimer.claim(1).get(0);
        assertThat(recorder.record(t1,classifier.classify(r1)).state()).isEqualTo(RecordResult.State.STALE);
        assertThat(gatewayJdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("PENDING");
        assertThat(gatewayJdbc.queryForObject("SELECT claim_token::text FROM outbox_event",String.class)).isEqualTo(t2.claimToken());
        assertThat(gatewayJdbc.queryForObject("SELECT late_outcome FROM delivery_attempt WHERE claim_token=?::uuid",String.class,t1.claimToken())).isEqualTo("SUCCESS");
        assertThat(recorder.record(t2,classifier.classify(client.send(t2.eventId(),t2.claimToken(),t2.payload()))).state()).isEqualTo(RecordResult.State.SENT);
        assertThat(gatewayJdbc.queryForList("SELECT outcome FROM delivery_attempt ORDER BY id",String.class)).containsExactly("ABANDONED","SUCCESS");
        assertStandard("S9",1);
    }
}
