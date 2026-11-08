package com.hopoong.diag.gateway.campaign;
import com.hopoong.diag.testsupport.CampaignFixture;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CampaignIT extends CampaignITSupport {
    @Test void createsSharedSnapshotAndPayloadAndQueriesDelivery() throws Exception {
        var response=create(CampaignFixture.request("req-1","TCM","L-A")).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.targetCount").value(4)).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).at("/data/campaignId").asLong();
        var payload=json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_event",String.class));
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId","source","campaign","vehicles");
        assertThat(payload.get("vehicles").size()).isEqualTo(4);
        var forward=json.readTree(mvc.perform(get("/api/v1/trace/lots/L-A/vehicles").param("ecuId","TCM")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(payload.get("vehicles").findValuesAsText("vin")).isEqualTo(forward.at("/data/vehicles").findValuesAsText("vin"));
        assertThat(jdbc.queryForObject("SELECT result_id FROM outbox_event",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT request_hash FROM campaign",String.class)).hasSize(64);
        mvc.perform(get("/api/v1/campaigns/{id}",id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicles").doesNotExist()).andExpect(jsonPath("$.data.delivery.status").value("PENDING"));
        mvc.perform(get("/api/v1/campaigns/{id}",id).param("includeVehicles","true")).andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicles.length()").value(4));
    }
    @Test void duplicateSurvivesRemovalAndConflictUsesAllRequestFields() throws Exception {
        var request=CampaignFixture.request("req-1","TCM","L-A");create(request).andExpect(status().isAccepted());
        jdbc.update("UPDATE vehicle_part SET removed_at=now() WHERE removed_at IS NULL");
        create(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("DUPLICATE"));
        var fields=json.convertValue(request,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        for (var change:Map.of("ecuId","BMS","lotNo","L-B","dtcCode","P0999","reason","다른 캠페인 사유로 요청합니다","requestedBy","ops-other").entrySet()) {
            var changed=new HashMap<>(fields);changed.put(change.getKey(),change.getValue());
            create(changed).andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:campaign-request-conflict"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign",Integer.class)).isEqualTo(1);
    }
    @Test void rejectsEmptyTargetsAndMalformedInput() throws Exception {
        create(CampaignFixture.request("r1","TCM","L-ZZ")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:no-campaign-target"));
        jdbc.update("UPDATE vehicle_part SET removed_at=now() WHERE removed_at IS NULL");
        create(CampaignFixture.request("r2","TCM","L-A")).andExpect(status().isUnprocessableEntity());
        var fields=json.convertValue(CampaignFixture.request("r","TCM","L-A"),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        for (var change:Map.of("requestId","a b","lotNo","L@1","reason","123456789","unknown","x").entrySet()) {
            var changed=new HashMap<>(fields);changed.put(change.getKey(),change.getValue());create(changed).andExpect(status().isBadRequest());
        }
        for (String field:fields.keySet()) {var changed=new HashMap<>(fields);changed.remove(field);create(changed).andExpect(status().isBadRequest());}
        mvc.perform(get("/api/v1/campaigns/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/campaigns/abc")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM campaign",Integer.class)).isZero();
    }
}
