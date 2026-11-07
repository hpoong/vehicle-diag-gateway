package com.hopoong.diag.gateway.delivery.application;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.hopoong.diag.gateway.delivery.config.DeliveryProperties;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;
import com.hopoong.diag.gateway.delivery.persistence.DeliveryAttemptMapper;
import com.hopoong.diag.gateway.delivery.persistence.OutboxMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryRecorder {
    private final OutboxMapper outbox;
    private final DeliveryAttemptMapper attempts;
    private final BackoffPolicy backoff;
    private final DeliveryProperties properties;

    @Transactional(rollbackFor = Exception.class)
    public RecordResult record(ClaimedEvent event, DeliveryOutcome outcome) {
        int updated;
        RecordResult result;
        switch (outcome.outcome()) {
            case SUCCESS -> {
                updated = outbox.markSent(event.id(), event.claimToken(), outcome.httpStatus());
                result = new RecordResult(RecordResult.State.SENT, null, null);
            }
            case RETRYABLE, UNCERTAIN -> {
                if (event.attemptCount() >= properties.maxAttempts()) {
                    updated = outbox.markDead(event.id(), event.claimToken(), "MAX_ATTEMPTS",
                            outcome.outcome().name(), outcome.httpStatus(), outcome.errorMessage());
                    result = new RecordResult(RecordResult.State.DEAD, null, "MAX_ATTEMPTS");
                    break;
                }
                Duration delay = backoff.delay(event.attemptCount());
                updated = outbox.reschedule(event.id(), event.claimToken(), delay.toMillis(),
                        outcome.outcome().name(), outcome.httpStatus(), outcome.errorMessage());
                result = new RecordResult(RecordResult.State.RETRY, delay, null);
            }
            case NON_RETRYABLE -> {
                updated = outbox.markDead(event.id(), event.claimToken(), "NON_RETRYABLE",
                        outcome.outcome().name(), outcome.httpStatus(), outcome.errorMessage());
                result = new RecordResult(RecordResult.State.DEAD, null, "NON_RETRYABLE");
            }
            default -> throw new IllegalArgumentException("Not a completed delivery outcome: " + outcome.outcome());
        }
        if (updated == 0) {
            boolean lateRecorded = attempts.recordLateOutcome(event.claimToken(), outcome.outcome().name()) == 1;
            log.warn("STALE_CLAIM outcome={} lateRecorded={}", outcome.outcome(), lateRecorded);
            return new RecordResult(RecordResult.State.STALE, null, null);
        }
        if (attempts.finish(event.claimToken(), outcome.outcome().name(), outcome.httpStatus(),
                outcome.errorCode(), outcome.errorMessage(), outcome.latencyMs()) != 1) {
            throw new IllegalStateException("Current claim has no STARTED attempt");
        }
        if (result.state() == RecordResult.State.SENT) log.info("OUTBOX_SENT attempts={}", event.attemptCount());
        if (result.state() == RecordResult.State.DEAD) log.info("OUTBOX_DEAD reason={}", result.deadReason());
        return result;
    }
}
