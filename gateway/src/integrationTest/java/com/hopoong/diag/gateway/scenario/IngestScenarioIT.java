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

class IngestScenarioIT extends ScenarioITSupport {
    static ReceiverRunner RECEIVER;
    @BeforeAll static void startReceiver() { RECEIVER = ReceiverRunner.start(false); }
    @AfterAll static void stopReceiver() { if (RECEIVER != null) RECEIVER.close(); }
    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry r) { r.add("gateway.delivery.receiver-url", () -> RECEIVER.url()); }
    @Override protected ReceiverRunner receiver() { return RECEIVER; }

    private String body(String event, String time) {
        return """
                {"deviceId":"scenario-device","eventId":"%s","brand":"ZETA","vin":"1HGCM82633A004352",
                 "sessionId":"session","occurredAt":"%s",
                 "ecus":[{"ecuId":"BMS","dtcCodes":["P0A80"]}]}
                """.formatted(event,time);
    }
    private org.springframework.test.web.servlet.ResultActions ingestBody(String body) throws Exception {
        return mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON).content(body));
    }
    @Test void s01_concurrentIdenticalRequestsAcceptExactlyOnce() throws Exception {
        String body=body(UUID.randomUUID().toString(),"2026-11-06T11:00:00Z");
        var pool=Executors.newFixedThreadPool(5); var ready=new CountDownLatch(5); var go=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<Integer>>();
            for(int i=0;i<5;i++) futures.add(pool.submit(() -> {
                ready.countDown(); if(!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return ingestBody(body).andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
            var codes=new ArrayList<Integer>(); for(var future:futures) codes.add(future.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(202,200,200,200,200);
        } finally { go.countDown(); pool.shutdownNow(); }
        drain(3); assertStandard("S1",1);
    }
    @Test void s02_sameKeyDifferentContentDoesNotChangeStoredData() throws Exception {
        String body=body(UUID.randomUUID().toString(),"2026-11-06T11:00:00Z");
        ingestBody(body).andExpect(status().isAccepted());
        var before=gatewayJdbc.queryForMap("SELECT payload_hash, raw_payload::text FROM diagnostic_result");
        var payload=gatewayJdbc.queryForObject("SELECT payload::text FROM outbox_event",String.class);
        var observation=gatewayJdbc.queryForObject("SELECT dtc_codes::text FROM ecu_observation",String.class);
        ingestBody(body.replace("P0A80","P0123")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:event-conflict"));
        assertThat(gatewayJdbc.queryForMap("SELECT payload_hash, raw_payload::text FROM diagnostic_result")).isEqualTo(before);
        assertThat(gatewayJdbc.queryForObject("SELECT payload::text FROM outbox_event",String.class)).isEqualTo(payload);
        assertThat(gatewayJdbc.queryForObject("SELECT dtc_codes::text FROM ecu_observation",String.class)).isEqualTo(observation);
        drain(3); assertStandard("S2",1);
    }
    @Test void s03_latePastResultDoesNotRegressLatest() throws Exception {
        ingestBody(body(UUID.randomUUID().toString(),"2026-11-06T11:00:00Z")).andExpect(status().isAccepted());
        ingestBody(body(UUID.randomUUID().toString(),"2026-11-06T10:00:00Z")).andExpect(status().isAccepted());
        assertThat(gatewayJdbc.queryForObject("SELECT occurred_at FROM vehicle_ecu_latest",java.time.OffsetDateTime.class).toInstant())
                .isEqualTo(java.time.Instant.parse("2026-11-06T11:00:00Z"));
        drain(3); assertStandard("S3",2);
    }
}
