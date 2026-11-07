package com.hopoong.diag.gateway.ops;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.gateway.delivery.application.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class OpsAttemptsIT extends OpsITSupport {

    @Test void attemptsAreIsolatedOrderedAndExposeOutcomeFields() throws Exception {
        long id=fixture.createPending(1).get(0);
        recorder.record(claimer.claim(1).get(0),outcome(AttemptOutcome.RETRYABLE));
        jdbc.update("UPDATE outbox_event SET next_attempt_at=now()");
        claimer.claim(1); jdbc.update("UPDATE outbox_event SET locked_until=now()-interval '1 second'");
        recorder.record(claimer.claim(1).get(0),outcome(AttemptOutcome.SUCCESS));
        fixture.createPending(1); recorder.record(claimer.claim(1).get(0),outcome(AttemptOutcome.SUCCESS));
        mvc.perform(get("/ops/events/{id}/attempts",id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].outcome").value("RETRYABLE"))
                .andExpect(jsonPath("$.data[0].httpStatus").value(503)).andExpect(jsonPath("$.data[0].errorCode").value("HTTP_503"))
                .andExpect(jsonPath("$.data[0].latencyMs").value(12)).andExpect(jsonPath("$.data[0].workerId").isNotEmpty())
                .andExpect(jsonPath("$.data[1].outcome").value("ABANDONED"))
                .andExpect(jsonPath("$.data[2].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.data[2].attemptNo").value(3)).andExpect(jsonPath("$.data[2].cycleNo").value(1));
    }
    @Test void emptyMissingAndMalformedIdsAreDistinct() throws Exception {
        long id=fixture.createPending(1).get(0);
        mvc.perform(get("/ops/events/{id}/attempts",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(get("/ops/events/999999/attempts")).andExpect(status().isNotFound()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:not-found"));
        mvc.perform(get("/ops/events/abc/attempts")).andExpect(status().isBadRequest());
    }
}
