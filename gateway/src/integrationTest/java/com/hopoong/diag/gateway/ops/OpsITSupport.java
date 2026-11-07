package com.hopoong.diag.gateway.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.delivery.application.*;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.testsupport.OutboxFixture;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(properties = {"gateway.delivery.enabled=false", "gateway.delivery.max-attempts=3", "gateway.delivery.backoff.base=0s"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class OpsITSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestService ingest;
    @Autowired OutboxClaimer claimer;
    @Autowired DeliveryRecorder recorder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    OutboxFixture fixture;
    @BeforeEach void reset() { fixture=new OutboxFixture(ingest,jdbc); fixture.reset(); }
    DeliveryOutcome outcome(AttemptOutcome value) {
        return new DeliveryOutcome(value, value == AttemptOutcome.SUCCESS ? 200 : 503,
                value == AttemptOutcome.SUCCESS ? null : "HTTP_503", null, 12);
    }
}
