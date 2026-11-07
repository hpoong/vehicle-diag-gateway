package com.hopoong.diag.gateway.delivery.client;

import com.hopoong.diag.testsupport.StubReceiver;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.hopoong.diag.gateway.delivery.application.OutcomeClassifier;
import com.hopoong.diag.gateway.delivery.config.DeliveryProperties;

class ReceiverClientIT {
    private ReceiverClient client(String url) {
        return new ReceiverClient(new DeliveryProperties(false, Duration.ofMillis(500), 4, Duration.ofSeconds(30),
                Duration.ofMillis(500), Duration.ofMillis(500), 3,
                new DeliveryProperties.Backoff(Duration.ZERO, 2, Duration.ofMinutes(5)), url, "test"));
    }
    @Test void preservesUtf8PayloadHeadersAndReturnsHttpErrorsWithoutRetry() {
        try (var stub = new StubReceiver()) {
            var client = client(stub.url());
            String event = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
            String payload = " {\"eventId\":\"" + event + "\",\"message\":\"한글\"} ";
            for (int status : new int[]{200,409,503,302}) {
                stub.reset(); stub.respond(status);
                var result = client.send(event, token, payload);
                assertThat(result.httpStatus()).isEqualTo(status);
                assertThat(result.error()).isNull();
                assertThat(stub.requests()).hasSize(1);
                var request = stub.requests().get(0);
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/api/v1/quality-events");
                assertThat(request.contentType()).isEqualTo("application/json");
                assertThat(request.eventId()).isEqualTo(event);
                assertThat(request.requestId()).isEqualTo(token);
                assertThat(request.body()).isEqualTo(payload);
            }
        }
    }
    @Test void classifiesRealReadTimeoutConnectionFailureAndClosedConnection() {
        var classifier = new OutcomeClassifier();
        try (var stub = new StubReceiver()) {
            stub.hold(Duration.ofSeconds(3));
            var result = client(stub.url()).send("event", "token", "{}");
            assertThat(classifier.classify(result).errorCode()).isEqualTo("READ_TIMEOUT");
            assertThat(result.latencyMs()).isLessThan(3000);
            assertThat(stub.requests()).hasSize(1);
        }
        assertThat(classifier.classify(client("http://127.0.0.1:" + StubReceiver.unusedPort()).send("e","t","{}")).errorCode())
                .isEqualTo("CONNECT_FAILED");
        try (var closed = StubReceiver.closeWithoutResponse()) {
            assertThat(classifier.classify(client(closed.url()).send("e","t","{}")).errorCode()).isEqualTo("CONNECTION_CLOSED");
        }
    }
}
