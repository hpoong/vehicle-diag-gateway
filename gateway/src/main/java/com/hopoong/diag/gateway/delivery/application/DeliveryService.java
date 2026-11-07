package com.hopoong.diag.gateway.delivery.application;

import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import com.hopoong.diag.gateway.delivery.client.ReceiverClient;
import com.hopoong.diag.gateway.delivery.config.DeliveryProperties;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryService {
    private final OutboxClaimer claimer;
    private final ReceiverClient client;
    private final OutcomeClassifier classifier;
    private final DeliveryRecorder recorder;
    private final DeliveryProperties properties;
    private final ThreadPoolTaskExecutor deliveryExecutor;

    public RunSummary runOnce() {
        var events = claimer.claim(properties.batchSize());
        var futures = events.stream().map(event -> CompletableFuture.supplyAsync(() -> deliver(event), deliveryExecutor)).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        int sent = 0, retry = 0, dead = 0, stale = 0, failed = 0;
        for (var future : futures) {
            var result = future.join();
            if (result == null) { failed++; continue; }
            switch (result.state()) {
                case SENT -> sent++;
                case RETRY -> retry++;
                case DEAD -> dead++;
                case STALE -> stale++;
            }
        }
        return new RunSummary(events.size(), sent, retry, dead, stale, failed);
    }

    private RecordResult deliver(ClaimedEvent event) {
        try (var ignored = DeliveryMdc.open(event)) {
            var outcome = classifier.classify(client.send(event.eventId(), event.claimToken(), event.payload()));
            var result = recorder.record(event, outcome);
            String next = result.state() == RecordResult.State.RETRY
                    ? "PENDING+" + result.nextDelay().toMillis() + "ms" : result.state().name();
            log.info("DELIVERY_RESULT outcome={} http={} latencyMs={} next={}", outcome.outcome(),
                    outcome.httpStatus() == null ? "-" : outcome.httpStatus(), outcome.latencyMs(), next);
            return result;
        } catch (Exception ex) {
            log.error("DELIVERY_RECORD_FAILED outboxId={} claimToken={}", event.id(), event.claimToken(), ex);
            return null;
        }
    }
}
