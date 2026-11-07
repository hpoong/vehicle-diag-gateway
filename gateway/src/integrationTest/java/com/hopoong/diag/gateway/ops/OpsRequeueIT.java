package com.hopoong.diag.gateway.ops;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.gateway.delivery.application.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class OpsRequeueIT extends OpsITSupport {

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(long id) {
        return post("/ops/events/{id}/requeue",id).header("X-Operator","kim.ops").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"receiver 점검 종료 확인 후 재처리\"}");
    }
    @Test void requeueResetsStateAndAtomicallyAuditsRequestAndNextCycle() throws Exception {
        long id=fixture.createPending(1).get(0);
        for(int i=0;i<3;i++) {
            jdbc.update("UPDATE outbox_event SET next_attempt_at=now()");
            recorder.record(claimer.claim(1).get(0),outcome(AttemptOutcome.RETRYABLE));
        }
        String requestId=UUID.randomUUID().toString();
        mvc.perform(request(id).header("X-Request-Id",requestId)).andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id",requestId)).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.attemptCount").value(0)).andExpect(jsonPath("$.data.cycleNo").value(2))
                .andExpect(jsonPath("$.data.deadReason").isEmpty()).andExpect(jsonPath("$.data.deadAt").isEmpty());
        var log=jdbc.queryForMap("SELECT * FROM ops_action_log");
        assertThat(log).containsEntry("action","REQUEUE").containsEntry("operator","kim.ops")
                .containsEntry("reason","receiver 점검 종료 확인 후 재처리").containsEntry("before_status","DEAD")
                .containsEntry("after_status","PENDING").containsEntry("before_attempt_count",3)
                .containsEntry("new_cycle_no",2).containsEntry("request_id",requestId);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at <= now() AND claim_token IS NULL AND locked_by IS NULL AND locked_until IS NULL FROM outbox_event",Boolean.class)).isTrue();
        mvc.perform(request(id)).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:invalid-state-transition"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_action_log",Integer.class)).isEqualTo(1);
        recorder.record(claimer.claim(1).get(0),outcome(AttemptOutcome.SUCCESS));
        mvc.perform(get("/ops/events/{id}/attempts",id)).andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[3].cycleNo").value(2)).andExpect(jsonPath("$.data[3].attemptNo").value(1))
                .andExpect(jsonPath("$.data[3].outcome").value("SUCCESS"));
    }
    @Test void nonDeadAndMissingRowsDoNotChange() throws Exception {
        var ids=fixture.createPending(2); fixture.markSent(ids.get(1));
        for(long id:ids) mvc.perform(request(id)).andExpect(status().isConflict());
        mvc.perform(request(999999)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_action_log",Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM outbox_event ORDER BY id",String.class)).containsExactly("PENDING","SENT");
    }
    @Test void invalidInputDoesNotMutateDeadRow() throws Exception {
        long id=fixture.createPending(1).get(0); fixture.markDead(id,"MAX_ATTEMPTS",3);
        for(String operator:List.of("", "x".repeat(65),"   ")) {
            mvc.perform(post("/ops/events/{id}/requeue",id).header("X-Operator",operator)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"1234567890\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/ops/events/{id}/requeue",id).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"1234567890\"}")).andExpect(status().isBadRequest());
        var bodies=new ArrayList<String>(List.of("{}","{\"reason\":1234567890}","{\"reason\":\"1234567890\",\"x\":1}"));
        for(String reason:List.of("123456789","x".repeat(501)," ".repeat(10),"😀".repeat(5),"정상 사유입니다"+(char)0+"끝")) bodies.add(json.writeValueAsString(Map.of("reason",reason)));
        for(String body:bodies) mvc.perform(request(id).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:invalid-request"));
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event",String.class)).isEqualTo("DEAD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_action_log",Integer.class)).isZero();
    }
    @Test void reasonLengthUsesUnicodeCodePoints() throws Exception {
        long id=fixture.createPending(1).get(0); fixture.markDead(id,"NON_RETRYABLE",1);
        mvc.perform(request(id).content(json.writeValueAsString(Map.of("reason","😀".repeat(500))))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT char_length(reason) FROM ops_action_log",Integer.class)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT before_attempt_count FROM ops_action_log",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentRequeueCommitsExactlyOnce() throws Exception {
        long id=fixture.createPending(1).get(0); fixture.markDead(id,"MAX_ATTEMPTS",3);
        var ready=new CountDownLatch(2); var go=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> action=() -> { ready.countDown(); if(!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("start timeout"); return mvc.perform(request(id)).andReturn().getResponse().getStatus(); };
            var a=pool.submit(action); var c=pool.submit(action); assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),c.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        } finally { go.countDown(); pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_action_log",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT cycle_no FROM outbox_event",Integer.class)).isEqualTo(2);
    }
}
