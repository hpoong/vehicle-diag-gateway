package com.hopoong.diag.gateway;

import com.hopoong.diag.gateway.ingest.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.flyway.enabled=false")
@AutoConfigureMockMvc
class IngestRequestValidationIT {
    @Autowired MockMvc mvc;
    @MockitoBean IngestService service;

    private static final String VALID = """
            {"deviceId":"device_1","eventId":"event-1","brand":"BRAND","vin":"1HGCM82633A004352",
             "sessionId":"session","occurredAt":"2026-11-06T08:10:00Z",
             "ecus":[{"ecuId":"ECU_1","swVersion":"1","dtcCodes":["P0123"]}]}
            """;

    private void rejected(String json) throws Exception {
        mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:invalid-request"));
        verifyNoInteractions(service);
    }

    @Test
    void reportsAllNestedValidationErrors() throws Exception {
        String json = VALID.replace("1HGCM82633A004352", "1HGCM82633A00435")
                .replace("\"1\",\"dtcCodes\"", "\"\",\"dtcCodes\"").replace("P0123", "X123");
        mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:invalid-request"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "vin", "ecus[0].swVersion", "ecus[0].dtcCodes[0]")));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidSizesAndJson() throws Exception {
        rejected(VALID.replace("device_1", "a".repeat(51)));
        rejected(VALID.replace("[{\"ecuId\":\"ECU_1\",\"swVersion\":\"1\",\"dtcCodes\":[\"P0123\"]}]",
                "[" + ("{\"ecuId\":\"E\",\"dtcCodes\":[]},".repeat(30)) + "{\"ecuId\":\"E\",\"dtcCodes\":[]} ]"));
        rejected(VALID.replace("\"deviceId\"", "\"unknown\":1,\"deviceId\""));
        rejected("{");
        rejected(VALID.replace("\"2026-11-06T08:10:00Z\"", "1730000000"));
        rejected(VALID.replace("[{\"ecuId\":\"ECU_1\",\"swVersion\":\"1\",\"dtcCodes\":[\"P0123\"]}]", "[null]"));
    }

    @Test
    void passesValidRequestWithEmptyEcusToService() throws Exception {
        String json = VALID.replace("[{\"ecuId\":\"ECU_1\",\"swVersion\":\"1\",\"dtcCodes\":[\"P0123\"]}]", "[]");
        mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        verify(service).ingest(any());
    }
    @Test
    void rejectsMissingOffsetWithFieldError() throws Exception {
        mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("2026-11-06T08:10:00Z", "2026-11-06T08:10:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("occurredAt"));
        verifyNoInteractions(service);
    }
}
