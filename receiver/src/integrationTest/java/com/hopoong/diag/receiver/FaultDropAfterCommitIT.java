package com.hopoong.diag.receiver;

import com.hopoong.diag.receiver.fault.*;
import com.hopoong.diag.testsupport.QualityEventFixture;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("chaos")
class FaultDropAfterCommitIT extends ReceiverDatabaseITSupport {
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpResponse<String> send(String body, int timeoutSeconds) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/quality-events"))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test
    void timeoutHappensAfterCommitAndRetryRecognizesDuplicate() throws Exception {
        assertThat(post(QualityEventFixture.sample(UUID.randomUUID().toString())).getStatusCode().value()).isEqualTo(200);
        String id = UUID.randomUUID().toString();
        String body = QualityEventFixture.sample(id);
        faultState.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 2000));
        assertThatThrownBy(() -> send(body, 1)).isInstanceOf(HttpTimeoutException.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (jdbc.queryForObject("SELECT count(*) FROM quality_case WHERE event_id=?::uuid", Integer.class, id) != 3
                && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM received_event WHERE event_id=?::uuid", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quality_case WHERE event_id=?::uuid", Integer.class, id)).isEqualTo(3);
        faultState.set(new FaultConfig(FaultMode.NONE, 15000));
        var retry = post(body);
        assertThat(retry.getBody().at("/data/result").asText()).isEqualTo("DUPLICATE");
        assertThat(retry.getBody().at("/data/caseCount").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quality_case WHERE event_id=?::uuid", Integer.class, id)).isEqualTo(3);
    }
    @Test
    void holdsSuccessfulResponsesButNeverConflictOrValidationErrors() throws Exception {
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        faultState.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 1500));
        long start = System.nanoTime();
        assertThat(send(body, 5).statusCode()).isEqualTo(200);
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(Duration.ofMillis(1500).toNanos());
        faultState.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 2000));
        assertThat(send(body.replace("P0A80", "P0B00"), 1).statusCode()).isEqualTo(409);
        assertThat(send(body.replaceFirst("[0-9a-f]{8}-[0-9a-f-]{27}", "x"), 1).statusCode()).isEqualTo(400);
    }
}
