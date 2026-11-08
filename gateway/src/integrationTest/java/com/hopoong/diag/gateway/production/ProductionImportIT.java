package com.hopoong.diag.gateway.production;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ProductionImportIT extends ProductionITSupport {
    Map<String,Object> part(String vin,String serial,String lot,String installed,String removed) {
        var row=new LinkedHashMap<String,Object>();
        row.put("vin",vin);row.put("ecuId","TCM");row.put("partNo","P");row.put("serialNo",serial);row.put("lotNo",lot);row.put("installedAt",installed);row.put("removedAt",removed);return row;
    }
    Map<String,Object> part(String serial,String lot,String removed) {return part("KMHXX00XXXX000001",serial,lot,"2026-01-01T00:00:00Z",removed);}
    org.springframework.test.web.servlet.ResultActions send(List<?> parts) throws Exception {
        return mvc.perform(post("/api/v1/production/vehicle-parts").contentType("application/json").content(json.writeValueAsString(Map.of("source","OEM-SIM","batchId","b-1","parts",parts))));
    }
    @Test void insertRepeatAndCorrection() throws Exception {
        var rows=List.of(part("A","L",null),part("KMHXX00XXXX000002","B",null,"2026-01-01T00:00:00Z",null),part("KMHXX00XXXX000003","C","L","2026-01-01T00:00:00Z",null));
        send(rows).andExpect(status().isOk()).andExpect(jsonPath("$.data.inserted").value(3));
        var before=jdbc.queryForList("SELECT updated_at FROM vehicle_part ORDER BY id");
        send(rows).andExpect(status().isOk()).andExpect(jsonPath("$.data.unchanged").value(3));
        assertThat(jdbc.queryForList("SELECT updated_at FROM vehicle_part ORDER BY id")).isEqualTo(before);
        rows.get(1).put("lotNo","L-1");
        send(rows).andExpect(status().isOk()).andExpect(jsonPath("$.data.updated").value(1)).andExpect(jsonPath("$.data.unchanged").value(2));
        assertThat(jdbc.queryForObject("SELECT updated_at>created_at FROM vehicle_part WHERE serial_no='B'",Boolean.class)).isTrue();
    }
    @Test void replacementRunsRemovalFirstAndConflictRollsBack() throws Exception {
        send(List.of(part("A","L",null))).andExpect(status().isOk());
        send(List.of(part("KMHXX00XXXX000001","B","L-2","2026-02-01T00:00:00Z",null),part("A","L","2026-02-01T00:00:00Z")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.inserted").value(1)).andExpect(jsonPath("$.data.updated").value(1));
        send(List.of(part("C","L",null),part("KMHXX00XXXX000002","D","L","2026-01-01T00:00:00Z","2026-02-01T00:00:00Z")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.type").value("urn:diag-gateway:error:current-part-conflict")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("KMHXX"))));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_part WHERE serial_no='D'",Integer.class)).isZero();
    }
    @Test void batchViolationsAndPrecision() throws Exception {
        send(List.of(part("A","L",null),part("B","L",null))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].rule").value("PART.MULTIPLE_CURRENT_PARTS"));
        var removed=part("A","L","2026-02-01T00:00:00Z");
        send(List.of(removed,removed)).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].rule").value("PART.DUPLICATE_PART_IN_BATCH"));
        send(List.of(part("KMHXX00XXXX000001","A","L","2026-01-01T00:00:00.0000001Z","2026-01-01T00:00:00.0000004Z"))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].rule").value("PART.INVALID_PART_PERIOD"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM vehicle_part",Integer.class)).isZero();
    }
    @Test void invalidRequests() throws Exception {
        send(List.of()).andExpect(status().isBadRequest());
        send(Collections.nCopies(1001,part("A","L",null))).andExpect(status().isBadRequest());
        for (var change: List.of(Map.entry("vin","KMHXX00XXXX00001"),Map.entry("lotNo",""),Map.entry("lotNo","L/1"),Map.entry("installedAt","2026-01-01T00:00:00"),Map.entry("unknown","x"))) {
            var row=part("A","L",null);row.put(change.getKey(),change.getValue());send(List.of(row)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/production/vehicle-parts").contentType("application/json").content("{\"source\":\"OEM\",\"batchId\":\"b\"}")).andExpect(status().isBadRequest());
    }
}
