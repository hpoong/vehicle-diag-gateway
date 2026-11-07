package com.hopoong.diag.gateway.delivery;

import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxClaimer {
    private final OutboxMapper outbox;
    private final DeliveryAttemptMapper attempts;
    private final DeliveryProperties properties;
    private final WorkerIdentity identity;

    @Transactional(rollbackFor = Exception.class)
    public List<ClaimedEvent> claim(int limit) {
        if (limit < 1) return List.of();
        var events = outbox.claim(limit, identity.instanceId(), properties.lease().toMillis())
                .stream().sorted(Comparator.comparingLong(ClaimedEvent::id)).toList();
        if (events.isEmpty()) return events;
        // End expired attempts before creating a new STARTED row (partial unique index).
        attempts.abandonStarted(events.stream().map(ClaimedEvent::id).toList());
        var deliverable = new java.util.ArrayList<ClaimedEvent>();
        for (var event : events) {
            if (event.attemptCount() > properties.maxAttempts()) {
                if (outbox.markSkippedDead(event.id(), event.claimToken()) != 1) {
                    throw new IllegalStateException("Lost claim inside TX1");
                }
                attempts.insertSkipped(event.id(), event.cycleNo(), event.attemptCount(), event.claimToken(),
                        identity.workerId(Thread.currentThread().getName()));
                try (var ignored = DeliveryMdc.open(event)) {
                    log.info("OUTBOX_DEAD reason=MAX_ATTEMPTS outcome=SKIPPED_MAX_ATTEMPTS");
                }
                continue;
            }
            deliverable.add(event);
            attempts.insertStarted(event.id(), event.cycleNo(), event.attemptCount(), event.claimToken(),
                    identity.workerId(Thread.currentThread().getName()));
        }
        return deliverable;
    }
}
