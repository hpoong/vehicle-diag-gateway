package com.hopoong.diag.receiver;

import com.hopoong.diag.testsupport.QualityEventFixture;
import com.hopoong.diag.receiver.fault.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("chaos")
@TestPropertySource(properties = "receiver.compare.naive-insert=true")
@ExtendWith(OutputCaptureExtension.class)
class NaiveCompareIT extends ReceiverDatabaseITSupport {
    @LocalServerPort int port;

    @Test
    void downRejectsAndRetryAfterLostResponseCreatesDuplicateCases() throws Exception {
        String warmupId = UUID.randomUUID().toString();
        assertThat(post(QualityEventFixture.sample(warmupId)).getStatusCode().value()).isEqualTo(200);
        jdbc.execute("TRUNCATE quality_case_naive RESTART IDENTITY");
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        faultState.set(new FaultConfig(FaultMode.DOWN, 0));
        assertThat(post(body).getStatusCode().value()).isEqualTo(503);
        assertEmpty();
        faultState.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 2000));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/quality-events"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(1))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var client = HttpClient.newHttpClient();
        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(HttpTimeoutException.class);
        assertThat(count("quality_case_naive")).isEqualTo(3);
        faultState.set(new FaultConfig(FaultMode.NONE, 15000));
        assertThat(post(body).getBody().at("/data/result").asText()).isEqualTo("APPLIED");
        assertThat(count("quality_case_naive")).isEqualTo(6);
        assertThat(duplicateGroups("quality_case_naive")).isEqualTo(3);
        assertThat(count("received_event")).isZero();
        assertThat(count("quality_case")).isZero();
    }

    @Test
    void repeatedEventsCreateThreeDuplicateGroups(CapturedOutput output) {
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        for (int i = 0; i < 3; i++) {
            var response = post(body);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().at("/data/result").asText()).isEqualTo("APPLIED");
            assertThat(response.getBody().at("/data/caseCount").asInt()).isEqualTo(3);
        }
        assertThat(count("quality_case_naive")).isEqualTo(9);
        assertThat(count("received_event")).isZero();
        assertThat(count("quality_case")).isZero();
        assertThat(duplicateGroups("quality_case_naive")).isEqualTo(3);
        assertThat(output.getOut()).contains("QUALITY_APPLIED caseCount=3 naive=true").doesNotContain("KMHXX00XXXX000001", "\"dtcCodes\"");
    }
    @Test
    void validationStillApplies() {
        for (String body : List.of(QualityEventFixture.sample("x"), QualityEventFixture.body(UUID.randomUUID().toString(), 1,
                new QualityEventFixture.Ecu("BMS", null, List.of("P0A80", "P0A80"))))) {
            assertThat(post(body).getStatusCode().value()).isEqualTo(400);
            assertEmpty();
        }
    }
}
