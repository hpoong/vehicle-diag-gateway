package com.hopoong.diag.receiver;

import com.hopoong.diag.testsupport.QualityEventFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("chaos")
class FaultDownIT extends ReceiverDatabaseITSupport {
    @Test
    void rejectsBeforeBodyParsingButAllowsHealthAndRecovery() {
        assertThat(postTo("/admin/faults", "{\"mode\":\"DOWN\"}", null).getStatusCode().value()).isEqualTo(200);
        String id = UUID.randomUUID().toString();
        for (String body : java.util.List.of(QualityEventFixture.sample(UUID.randomUUID().toString()), "{")) {
            var response = postTo("/api/v1/quality-events", body, id);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().path("type").asText()).isEqualTo("urn:diag-receiver:error:service-unavailable");
            assertThat(response.getBody().path("requestId").asText()).isEqualTo(id);
            assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(id);
            assertEmpty();
        }
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(postTo("/admin/faults", "{\"mode\":\"NONE\"}", null).getStatusCode().value()).isEqualTo(200);
        assertThat(post(QualityEventFixture.sample(id)).getBody().at("/data/result").asText()).isEqualTo("APPLIED");
    }
}
