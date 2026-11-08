package com.hopoong.diag.receiver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.receiver.fault.*;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
// 실행 셸의 DEBUG 값과 무관하게 기본 INFO 로그 계약을 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "debug=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class ReceiverDatabaseITSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TestRestTemplate http;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected FaultState faultState;

    @BeforeEach
    void reset() {
        faultState.set(new FaultConfig(FaultMode.NONE, FaultConfig.DEFAULT_HOLD_MS));
        jdbc.execute("TRUNCATE received_campaign_vehicle, received_campaign, quality_case, quality_case_naive, received_event RESTART IDENTITY CASCADE");
    }
    protected int count(String table) {
        checkTable(table);
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
    protected int duplicateGroups(String table) {
        checkTable(table);
        return jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM " + table
                + " GROUP BY event_id, ecu_id, dtc_code HAVING count(*) >= 2) g", Integer.class);
    }
    private void checkTable(String table) {
        if (!Set.of("received_event", "quality_case", "quality_case_naive", "received_campaign", "received_campaign_vehicle").contains(table)) {
            throw new IllegalArgumentException("Unknown test table");
        }
    }
    protected ResponseEntity<JsonNode> post(String body) { return postTo("/api/v1/quality-events", body, null); }
    protected ResponseEntity<JsonNode> postTo(String path, String body, String requestId) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (requestId != null) headers.set("X-Request-Id", requestId);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
    protected void assertEmpty() {
        org.assertj.core.api.Assertions.assertThat(count("received_campaign")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("received_campaign_vehicle")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("received_event")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("quality_case")).isZero();
        org.assertj.core.api.Assertions.assertThat(count("quality_case_naive")).isZero();
    }
}
