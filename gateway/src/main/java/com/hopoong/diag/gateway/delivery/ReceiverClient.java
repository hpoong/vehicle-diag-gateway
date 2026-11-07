package com.hopoong.diag.gateway.delivery;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

@Component
public class ReceiverClient {
    private final RestClient rest;

    public ReceiverClient(DeliveryProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder().requestFactory(factory).baseUrl(properties.receiverUrl()).build();
    }

    public SendResult send(String eventId, String claimToken, String payloadJson) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Receiver HTTP must run outside a transaction");
        }
        long start = System.nanoTime();
        try {
            int status = rest.post().uri("/api/v1/quality-events").contentType(MediaType.APPLICATION_JSON)
                    .header("X-Event-Id", eventId).header("X-Request-Id", claimToken)
                    .body(payloadJson.getBytes(StandardCharsets.UTF_8))
                    .exchange((request, response) -> response.getStatusCode().value());
            return SendResult.responded(status, elapsed(start));
        } catch (Exception ex) {
            return SendResult.failed(ex, elapsed(start));
        }
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
