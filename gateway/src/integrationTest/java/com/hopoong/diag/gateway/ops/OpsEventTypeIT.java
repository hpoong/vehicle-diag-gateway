package com.hopoong.diag.gateway.ops;
import com.hopoong.diag.testsupport.CampaignOutboxFixture;
import org.junit.jupiter.api.Test;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class OpsEventTypeIT extends OpsITSupport {
    @Test void campaignRowsAreNullSafeAndCanBeRequeued() throws Exception {
        fixture.createPending(2);long id=CampaignOutboxFixture.create(jdbc);
        mvc.perform(get("/ops/events").param("status","PENDING")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[2].resultId").doesNotExist()).andExpect(jsonPath("$.data[2].eventType").value("CAMPAIGN")).andExpect(jsonPath("$.data[2].campaignId").isNumber());
        fixture.markDead(id,"NON_RETRYABLE",1);
        mvc.perform(post("/ops/events/{id}/requeue",id).header("X-Operator","ops-it").contentType("application/json").content("{\"reason\":\"receiver 점검 완료 후 재처리\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.eventType").value("CAMPAIGN"));
    }
    @Test void campaignFixtureResetsBetweenTests() throws Exception {
        CampaignOutboxFixture.create(jdbc);
        mvc.perform(get("/ops/events").param("status","PENDING")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
    }
}
