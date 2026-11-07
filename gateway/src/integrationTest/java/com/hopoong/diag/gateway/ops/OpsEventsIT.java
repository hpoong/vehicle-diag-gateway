package com.hopoong.diag.gateway.ops;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.gateway.delivery.application.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class OpsEventsIT extends OpsITSupport {

    @Test void listsMatchSummaryAndExcludePayloadAndTokens() throws Exception {
        var ids=fixture.createPending(12);
        for(int i=4;i<9;i++) fixture.markSent(ids.get(i));
        for(int i=9;i<12;i++) fixture.markDead(ids.get(i),"MAX_ATTEMPTS",3);
        var summary=json.readTree(mvc.perform(get("/ops/summary")).andReturn().getResponse().getContentAsString()).path("data").path("counts");
        int total=0;
        for(String state:List.of("PENDING","SENT","DEAD")) {
            var response=mvc.perform(get("/ops/events").param("status",state).param("limit","200"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var data=json.readTree(response).path("data");
            assertThat(data.size()).isEqualTo(summary.path(state).asInt()); total+=data.size();
            for(var row:data) {
                assertThat(row.path("status").asText()).isEqualTo(state);
                for(String field:List.of("payload","vin","claimToken")) assertThat(row.has(field)).isFalse();
            }
        }
        assertThat(total).isEqualTo(summary.path("TOTAL").asInt());
    }
    @Test void statusOrdersUseTimestampThenId() throws Exception {
        for(String state:List.of("PENDING","SENT","DEAD")) {
            fixture.reset(); var ids=fixture.createPending(3);
            for(long id:ids) {
                if(state.equals("SENT")) fixture.markSent(id);
                if(state.equals("DEAD")) fixture.markDead(id,"MAX_ATTEMPTS",3);
            }
            String column=switch(state) {case "SENT" -> "sent_at"; case "DEAD" -> "dead_at"; default -> "next_attempt_at";};
            jdbc.update("UPDATE outbox_event SET "+column+"='2026-11-06T00:00:00Z'::timestamptz");
            jdbc.update("UPDATE outbox_event SET "+column+"='2026-11-07T00:00:00Z'::timestamptz WHERE id=?",ids.get(2));
            var data=json.readTree(mvc.perform(get("/ops/events").param("status",state)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
            var actual=new ArrayList<Long>(); data.forEach(r -> actual.add(r.path("outboxId").asLong()));
            assertThat(actual).containsExactlyElementsOf(state.equals("PENDING") ? ids : List.of(ids.get(2),ids.get(0),ids.get(1)));
        }
    }
    @Test void validatesStatusAndLimitAndAppliesDefault() throws Exception {
        fixture.createPending(60);
        mvc.perform(get("/ops/events").param("status","PENDING")).andExpect(jsonPath("$.data.length()").value(50));
        mvc.perform(get("/ops/events").param("status","PENDING").param("limit","2")).andExpect(jsonPath("$.data.length()").value(2));
        for(String limit:List.of("0","201","abc")) mvc.perform(get("/ops/events").param("status","PENDING").param("limit",limit))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:invalid-request"));
        mvc.perform(get("/ops/events")).andExpect(status().isBadRequest());
        for(String state:List.of("FOO","dead")) mvc.perform(get("/ops/events").param("status",state)).andExpect(status().isBadRequest());
    }
}
