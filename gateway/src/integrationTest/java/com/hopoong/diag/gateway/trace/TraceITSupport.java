package com.hopoong.diag.gateway.trace;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@SpringBootTest(properties={"gateway.delivery.enabled=false","gateway.trace.min-sample=4"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class TraceITSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IngestService ingest;
    TraceFixture fixture;
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE vehicle_part RESTART IDENTITY");
        new OutboxFixture(ingest,jdbc).reset();
        fixture=new TraceFixture(jdbc,ingest);fixture.load();
    }
}
