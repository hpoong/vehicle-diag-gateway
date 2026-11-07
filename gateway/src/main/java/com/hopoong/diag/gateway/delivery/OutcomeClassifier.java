package com.hopoong.diag.gateway.delivery;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OutcomeClassifier {
    public DeliveryOutcome classify(SendResult result) {
        if (result.httpStatus() != null) {
            int status = result.httpStatus();
            if (status >= 200 && status <= 299) {
                return new DeliveryOutcome(AttemptOutcome.SUCCESS, status, null, null, result.latencyMs());
            }
            boolean retryable = status == 408 || status == 429 || (status >= 500 && status <= 599);
            return new DeliveryOutcome(retryable ? AttemptOutcome.RETRYABLE : AttemptOutcome.NON_RETRYABLE,
                    status, "HTTP_" + status, "HTTP " + status, result.latencyMs());
        }
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = result.error(); cause != null && chain.size() < 10 && !chain.contains(cause);
             cause = cause.getCause()) chain.add(cause);
        String code;
        AttemptOutcome outcome = AttemptOutcome.UNCERTAIN;
        if (chain.stream().anyMatch(e -> e instanceof ConnectException || e instanceof HttpConnectTimeoutException)) {
            code = "CONNECT_FAILED";
            outcome = AttemptOutcome.RETRYABLE;
        } else if (chain.stream().anyMatch(HttpTimeoutException.class::isInstance)) {
            code = "READ_TIMEOUT";
        } else if (chain.stream().anyMatch(IOException.class::isInstance)) {
            code = "CONNECTION_CLOSED";
        } else {
            code = "UNEXPECTED_ERROR";
        }
        Throwable cause = chain.isEmpty() ? null : chain.get(chain.size() - 1);
        String message = cause == null ? code : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return new DeliveryOutcome(outcome, null, code, message.substring(0, Math.min(message.length(), 1000)), result.latencyMs());
    }
}
