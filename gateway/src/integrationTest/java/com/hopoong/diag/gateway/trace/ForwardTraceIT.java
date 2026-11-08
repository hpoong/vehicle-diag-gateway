package com.hopoong.diag.gateway.trace;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ForwardTraceIT extends TraceITSupport {
    @Test void currentRemovedAndLatestDiagnostics() throws Exception {
        mvc.perform(get("/api/v1/trace/lots/L-A/vehicles").param("ecuId","TCM"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentVehicles").value(4)).andExpect(jsonPath("$.data.excludedRemoved").value(1))
            .andExpect(jsonPath("$.data.vehicles[3].serialNo").value("S-04B"));
        mvc.perform(get("/api/v1/trace/lots/L-C/vehicles").param("ecuId","TCM"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentVehicles").value(1)).andExpect(jsonPath("$.data.excludedRemoved").value(1));
        mvc.perform(get("/api/v1/trace/lots/L-B/vehicles").param("ecuId","TCM"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.currentVehicles").value(6))
            .andExpect(jsonPath("$.data.vehicles[1].latestDtcCodes").isEmpty())
            .andExpect(jsonPath("$.data.vehicles[2].latestDiagAt").value("2026-11-01T00:00:00Z"))
            .andExpect(jsonPath("$.data.vehicles[2].latestDtcCodes[0]").value("P0700"))
            .andExpect(jsonPath("$.data.vehicles[5].latestDiagAt").doesNotExist()).andExpect(jsonPath("$.data.vehicles[5].latestDtcCodes").doesNotExist());
    }
    @Test void limitsAndEmptyResults() throws Exception {
        mvc.perform(get("/api/v1/trace/lots/L-B/vehicles").param("ecuId","TCM").param("limit","2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.vehicles.length()").value(2)).andExpect(jsonPath("$.data.currentVehicles").value(6)).andExpect(jsonPath("$.data.truncated").value(true));
        mvc.perform(get("/api/v1/trace/lots/L-ZZ/vehicles").param("ecuId","TCM")).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentVehicles").value(0));
        for (String limit:new String[]{"0","5001","abc"}) mvc.perform(get("/api/v1/trace/lots/L-B/vehicles").param("ecuId","TCM").param("limit",limit)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trace/lots/L@1/vehicles").param("ecuId","TCM")).andExpect(status().isBadRequest());
    }
    @Test void validatesAllParametersAndOtherEcuReturnsEmpty() throws Exception {
        mvc.perform(get("/api/v1/trace/lots/L-A/vehicles")).andExpect(status().isBadRequest());
        for (String ecu:new String[]{"tcm","X".repeat(31)}) mvc.perform(get("/api/v1/trace/lots/L-A/vehicles").param("ecuId",ecu)).andExpect(status().isBadRequest());
        for (String lot:new String[]{"X".repeat(31),"-L1"}) mvc.perform(get("/api/v1/trace/lots/{lot}/vehicles",lot).param("ecuId","TCM")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/trace/lots/L-A/vehicles").param("ecuId","BMS")).andExpect(status().isOk()).andExpect(jsonPath("$.data.currentVehicles").value(0));
    }

}
