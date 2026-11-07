package com.hopoong.diag.gateway.delivery;

import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.testsupport.OutboxFixture;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import com.hopoong.diag.gateway.delivery.application.AttemptOutcome;
import com.hopoong.diag.gateway.delivery.application.DeliveryOutcome;
import com.hopoong.diag.gateway.delivery.application.DeliveryRecorder;
import com.hopoong.diag.gateway.delivery.application.OutboxClaimer;
import com.hopoong.diag.gateway.delivery.config.DeliveryProperties;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;

@Testcontainers
@SpringBootTest(properties = "gateway.delivery.max-attempts=3")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class DeliveryDatabaseITSupport {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired IngestService ingest;
    @Autowired OutboxClaimer claimer;
    @Autowired DeliveryRecorder recorder;
    @Autowired DeliveryProperties properties;
    OutboxFixture fixture;
    @BeforeEach void resetDatabase() { fixture = new OutboxFixture(ingest, jdbc); fixture.reset(); }
    ClaimedEvent claimOne() { fixture.createPending(1); return claimer.claim(1).get(0); }
    void expire() { jdbc.update("UPDATE outbox_event SET locked_until=now()-interval '1 second' WHERE claim_token IS NOT NULL"); }
    String text(String column) { return jdbc.queryForObject("SELECT " + column + " FROM outbox_event ORDER BY id LIMIT 1", String.class); }
    List<String> outcomes() { return jdbc.queryForList("SELECT outcome FROM delivery_attempt ORDER BY id", String.class); }
    DeliveryOutcome outcome(AttemptOutcome value) {
        return switch (value) {
            case SUCCESS -> new DeliveryOutcome(value, 200, null, null, 12);
            case RETRYABLE -> new DeliveryOutcome(value, 503, "HTTP_503", "HTTP 503", 12);
            case UNCERTAIN -> new DeliveryOutcome(value, null, "READ_TIMEOUT", "timeout", 12);
            case NON_RETRYABLE -> new DeliveryOutcome(value, 409, "HTTP_409", "HTTP 409", 12);
            default -> throw new IllegalArgumentException();
        };
    }
}
