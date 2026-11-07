package com.hopoong.diag.gateway.ops;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.gateway.delivery.application.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class OpsSummaryIT extends OpsITSupport {

    @Test void mixedStatesAreAggregatedOnce() throws Exception {
        var ids=fixture.createPending(8);
        for(int i=2;i<5;i++) fixture.markSent(ids.get(i));
        fixture.markDead(ids.get(5),"NON_RETRYABLE",1);
        fixture.markDead(ids.get(6),"MAX_ATTEMPTS",10); fixture.markDead(ids.get(7),"MAX_ATTEMPTS",10);
        jdbc.update("UPDATE outbox_event SET created_at=now()-interval '600 seconds' WHERE id=?", ids.get(0));
        var before=jdbc.queryForObject("SELECT date_trunc('milliseconds',now())", java.time.OffsetDateTime.class).toInstant();
        var result=mvc.perform(get("/ops/summary")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.counts.PENDING").value(2)).andExpect(jsonPath("$.data.counts.SENT").value(3))
                .andExpect(jsonPath("$.data.counts.DEAD").value(3)).andExpect(jsonPath("$.data.counts.TOTAL").value(8))
                .andExpect(jsonPath("$.data.dead.NON_RETRYABLE").value(1)).andExpect(jsonPath("$.data.dead.MAX_ATTEMPTS").value(2)).andReturn();
        var data=json.readTree(result.getResponse().getContentAsString()).get("data");
        assertThat(data.get("oldestPendingAgeSeconds").asLong()).isBetween(600L,609L);
        assertThat(data.get("asOf").asText()).endsWith("Z");
        assertThat(java.time.Instant.parse(data.get("asOf").asText())).isBetween(before,jdbc.queryForObject("SELECT now()", java.time.OffsetDateTime.class).toInstant());
    }
    @Test void emptySummaryHasZeroCountsAndNullAge() throws Exception {
        var result=mvc.perform(get("/ops/summary")).andExpect(status().isOk()).andReturn();
        var data=json.readTree(result.getResponse().getContentAsString()).get("data");
        data.get("counts").forEach(v -> assertThat(v.asLong()).isZero());
        data.get("dead").forEach(v -> assertThat(v.asLong()).isZero());
        assertThat(data.get("oldestPendingAgeSeconds").isNull()).isTrue();
    }
    @Test void unsupportedMethodReturnsProblem() throws Exception {
        mvc.perform(post("/ops/summary")).andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
