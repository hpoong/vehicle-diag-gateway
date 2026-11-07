package com.hopoong.diag.gateway.delivery.application;

import java.util.Map;
import org.slf4j.MDC;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;

public final class DeliveryMdc {
    private DeliveryMdc() {}

    public static Scope open(ClaimedEvent event) {
        var scope = new Scope(MDC.getCopyOfContextMap());
        MDC.put("outboxId", Long.toString(event.id()));
        MDC.put("eventId", event.eventId());
        MDC.put("claimToken", event.claimToken());
        MDC.put("attemptNo", Integer.toString(event.attemptCount()));
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previous;
        private Scope(Map<String, String> previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
