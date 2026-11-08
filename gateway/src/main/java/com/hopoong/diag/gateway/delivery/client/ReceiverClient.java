package com.hopoong.diag.gateway.delivery.client;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import com.hopoong.diag.gateway.delivery.application.SendResult;
import com.hopoong.diag.gateway.delivery.config.DeliveryProperties;

@Component
public class ReceiverClient {
    private final RestClient rest;
    private static final java.util.Map<String,String> PATHS=java.util.Map.of(
            com.hopoong.diag.gateway.delivery.application.OutboxEventType.DIAGNOSTIC_RESULT.name(), "/api/v1/quality-events",
            com.hopoong.diag.gateway.delivery.application.OutboxEventType.CAMPAIGN.name(), "/api/v1/campaign-targets");

    public ReceiverClient(DeliveryProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.rest = RestClient.builder().requestFactory(factory).baseUrl(properties.receiverUrl()).build();
    }

    public SendResult send(String eventType, String eventId, String claimToken, String payloadJson) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Receiver HTTP must run outside a transaction");
        }
        String path = PATHS.get(eventType);
        if (path == null) throw new IllegalArgumentException("Unsupported eventType");
        long start = System.nanoTime();
        try {
            int status = rest.post().uri(path).contentType(MediaType.APPLICATION_JSON)
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
