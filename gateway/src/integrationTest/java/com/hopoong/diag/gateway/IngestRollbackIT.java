package com.hopoong.diag.gateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.ingest.application.*;
import com.hopoong.diag.gateway.ingest.domain.*;
import com.hopoong.diag.gateway.ingest.persistence.*;
import com.hopoong.diag.gateway.ingest.web.*;
import com.hopoong.diag.gateway.delivery.persistence.OutboxMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.gateway.ingest.application.OutboxDraft;
import com.hopoong.diag.gateway.ingest.application.OutboxPayloadFactory;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultRequest;

@Testcontainers @SpringBootTest
class IngestRollbackIT {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired IngestService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean OutboxMapper outbox;
    @MockitoBean OutboxPayloadFactory factory;
    private DiagnosticResultRequest request() throws Exception {
        return json.readValue("""
            {"deviceId":"rollback","eventId":"%s","brand":"ZETA","vin":"3HGCM82633A004352",
             "sessionId":"s","occurredAt":"2026-11-05T00:00:00Z","ecus":[{"ecuId":"BMS","dtcCodes":[]}]}
            """.formatted(UUID.randomUUID()), DiagnosticResultRequest.class);
    }
    private void empty() {
        for (String table : new String[]{"diagnostic_result","ecu_observation","vehicle_ecu_latest","outbox_event"})
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
    }
    @Test void outboxFailureRollsBackAllFourTables() throws Exception {
        when(factory.create(any(), anyLong(), any(), anyString())).thenReturn(new OutboxDraft(UUID.randomUUID(), "{}"));
        doThrow(new IllegalStateException("outbox failed")).when(outbox).insert(anyLong(), anyString(), anyString());
        var request=request();
        assertThatThrownBy(() -> service.ingest(request)).isInstanceOf(IllegalStateException.class);
        empty();
    }
    @Test void checkedSerializationFailureAlsoRollsBack() throws Exception {
        doAnswer(call -> { throw new com.fasterxml.jackson.core.JsonProcessingException("serialization failed") {}; })
                .when(factory).create(any(), anyLong(), any(), anyString());
        var request=request();
        assertThatThrownBy(() -> service.ingest(request)).isInstanceOf(Exception.class);
        empty();
    }
}
