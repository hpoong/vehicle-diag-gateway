package com.hopoong.diag.gateway.trace;
import org.junit.jupiter.api.Test;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ReverseTraceIT extends TraceITSupport {
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query() {
        return get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0700").param("from","2026-10-01T00:00:00Z").param("to","2026-11-01T00:00:00Z");
    }
    JsonNode data() throws Exception {return json.readTree(mvc.perform(query()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");}
    @Test void countsRatesBucketsSortingAndReplay() throws Exception {
        var d=data();assertThat(d.at("/totals/faultVehicles").asInt()).isEqualTo(8);assertThat(d.at("/totals/diagnosedVehicles").asInt()).isEqualTo(12);
        var lots=d.get("lots");assertThat(lots.findValuesAsText("lotNo")).containsExactly("L-A","L-C","L-B");
        int[][] expected={{4,5,5},{1,1,2},{0,3,6}};double[] rates={0.8,1,0},other={0.25,0.5,0.8333};
        for (int i=0;i<3;i++) {
            assertThat(lots.get(i).get("faultVehicles").asInt()).isEqualTo(expected[i][0]);
            assertThat(lots.get(i).get("diagnosedVehicles").asInt()).isEqualTo(expected[i][1]);
            assertThat(lots.get(i).get("installedVehicles").asInt()).isEqualTo(expected[i][2]);
            assertThat(lots.get(i).get("faultRate").asDouble()).isEqualTo(rates[i]);
            assertThat(lots.get(i).get("otherLotsFaultRate").asDouble()).isEqualTo(other[i]);
            assertThat(lots.get(i).get("lowSample").asBoolean()).isEqualTo(i!=0);
        }
        for (String bucket:new String[]{"unknownLot","noHistory","ambiguous"}) {
            assertThat(d.at("/unattributed/"+bucket+"/faultVehicles").asInt()).isEqualTo(1);
            assertThat(d.at("/unattributed/"+bucket+"/diagnosedVehicles").asInt()).isEqualTo(1);
        }
        assertThat(d.get("evidence").isNull()).isTrue();
        fixture.diagnoses();assertThat(data()).isEqualTo(d);
    }
    @Test void evidenceUsesFirstFaultBeforeReplacement() throws Exception {
        var d=json.readTree(mvc.perform(query().param("lotNo","L-A")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(d.at("/evidence/vehicles").findValuesAsText("vin")).containsExactly(TraceFixture.vin(1),TraceFixture.vin(2),TraceFixture.vin(3),TraceFixture.vin(8));
        assertThat(d.at("/evidence/vehicles/3/serialNo").asText()).isEqualTo("S-08A");
        assertThat(d.at("/evidence/truncated").asBoolean()).isFalse();
        mvc.perform(query().param("lotNo","L-ZZ")).andExpect(jsonPath("$.data.evidence.vehicles").isEmpty());
    }
    @Test void noEcuNoFaultAndOtherEcu() throws Exception {
        mvc.perform(get("/api/v1/trace/lots").param("ecuId","ABS").param("dtcCode","P0700")).andExpect(status().isOk()).andExpect(jsonPath("$.data.lots").isEmpty()).andExpect(jsonPath("$.data.totals.diagnosedVehicles").value(0));
        mvc.perform(get("/api/v1/trace/lots").param("ecuId","BMS").param("dtcCode","P0700").param("from","2026-10-01T00:00:00Z").param("to","2026-11-01T00:00:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.unattributed.noHistory.faultVehicles").value(1));
        mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0999").param("from","2026-10-01T00:00:00Z").param("to","2026-11-01T00:00:00Z"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.totals.faultVehicles").value(0));
    }
    @Test void validatesEcuLotAndExactLastDiagnosticAttribution() throws Exception {
        for (String ecu:List.of("tcm","X".repeat(31))) mvc.perform(get("/api/v1/trace/lots").param("ecuId",ecu).param("dtcCode","P0700")).andExpect(status().isBadRequest());
        for (String lot:List.of("L/1","X".repeat(31))) mvc.perform(query().param("lotNo",lot)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trace/lots").param("dtcCode","P0700")).andExpect(status().isBadRequest());
        var d=json.readTree(mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0999").param("from","2026-10-01T00:00:00Z").param("to","2026-11-01T00:00:00Z"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        var counts=new java.util.HashMap<String,Integer>();
        d.get("lots").forEach(l -> counts.put(l.get("lotNo").asText(),l.get("diagnosedVehicles").asInt()));
        assertThat(counts).containsEntry("L-A",4).containsEntry("L-B",4).containsEntry("L-C",1);
    }
    @Test void validatesAndDefaultsWindow() throws Exception {
        for (String dtc:new String[]{"p0700","P4000","P07001"}) mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode",dtc)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM")).andExpect(status().isBadRequest());
        for (String from:new String[]{"","2026-10-01","2026-10-01T00:00:00"}) mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0700").param("from",from)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0700").param("from","2026-11-01T00:00:00Z").param("to","2026-11-01T00:00:00Z")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rule").value("TRACE.WINDOW_ORDER"));
        mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0700").param("from","2026-01-01T00:00:00Z")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rule").value("TRACE.WINDOW_TOO_LONG"));
        var d=json.readTree(mvc.perform(get("/api/v1/trace/lots").param("ecuId","TCM").param("dtcCode","P0700")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(java.time.Duration.between(java.time.Instant.parse(d.get("from").asText()),java.time.Instant.parse(d.get("to").asText())).toDays()).isEqualTo(30);
    }
}
