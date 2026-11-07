package com.hopoong.diag.gateway.delivery.application;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.client.ResourceAccessException;
import static org.assertj.core.api.Assertions.*;

class OutcomeClassifierTest {
    private final OutcomeClassifier classifier = new OutcomeClassifier();
    @ParameterizedTest @CsvSource({"200,SUCCESS", "201,SUCCESS", "204,SUCCESS", "302,NON_RETRYABLE",
            "400,NON_RETRYABLE", "404,NON_RETRYABLE", "408,RETRYABLE", "409,NON_RETRYABLE",
            "422,NON_RETRYABLE", "429,RETRYABLE", "500,RETRYABLE", "503,RETRYABLE"})
    void classifiesHttp(int status, AttemptOutcome expected) {
        var outcome = classifier.classify(SendResult.responded(status, 12));
        assertThat(outcome.outcome()).isEqualTo(expected);
        assertThat(outcome.httpStatus()).isEqualTo(status);
        assertThat(outcome.latencyMs()).isEqualTo(12);
        assertThat(outcome.errorCode()).isEqualTo(expected == AttemptOutcome.SUCCESS ? null : "HTTP_" + status);
    }
    @Test void scansAllCausesWithConnectFailurePrecedence() {
        Throwable[] causes = {new ConnectException(), new IOException(new ConnectException()),
                new HttpConnectTimeoutException("connect"), new HttpTimeoutException("read"),
                new IOException("closed"), new IllegalStateException("unexpected")};
        String[] codes = {"CONNECT_FAILED", "CONNECT_FAILED", "CONNECT_FAILED", "READ_TIMEOUT", "CONNECTION_CLOSED", "UNEXPECTED_ERROR"};
        for (int i=0; i<causes.length; i++) {
            Throwable error = causes[i] instanceof IOException io ? new ResourceAccessException("wrapped", io) : causes[i];
            var o = classifier.classify(SendResult.failed(error, 3));
            assertThat(o.errorCode()).isEqualTo(codes[i]);
            assertThat(o.outcome()).isEqualTo(i<3 ? AttemptOutcome.RETRYABLE : AttemptOutcome.UNCERTAIN);
            assertThat(o.httpStatus()).isNull();
        }
        assertThat(classifier.classify(SendResult.failed(new IOException("x".repeat(5000)), 0)).errorMessage()).hasSize(1000);
        var a = new IOException("a"); var b = new IOException("b", a); a.initCause(b);
        assertThat(classifier.classify(SendResult.failed(a, 0)).errorCode()).isEqualTo("CONNECTION_CLOSED");
    }
}
