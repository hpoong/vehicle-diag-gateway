package com.hopoong.diag.gateway;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class BrandPolicyIT {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reportsAllBrandViolationsWithoutWritingToDatabase() throws Exception {
        String event = UUID.randomUUID().toString();
        String json = """
                {"deviceId":"device","eventId":"%s","brand":"BRAND_A","vin":"1HGCM82633A004352",
                 "sessionId":"session","occurredAt":"2026-11-06T08:10:00Z",
                 "ecus":[{"ecuId":"VCU","dtcCodes":[]},
                         {"ecuId":"BMS","swVersion":"1","dtcCodes":[]},
                         {"ecuId":"ABS","dtcCodes":[]}]}
                """.formatted(event);
        mvc.perform(post("/api/v1/diagnostic-results").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:policy-violation"))
                .andExpect(jsonPath("$.policyCode").value("BRAND_A-v1"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder(
                        "ecus[0].swVersion", "ecus[2].swVersion")))
                .andExpect(jsonPath("$.errors[*].rule").value(containsInAnyOrder(
                        "BRAND_A.SW_VERSION_REQUIRED", "BRAND_A.SW_VERSION_REQUIRED")));
        for (String table : new String[]{"diagnostic_result", "ecu_observation", "vehicle_ecu_latest", "outbox_event"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
    }
}
