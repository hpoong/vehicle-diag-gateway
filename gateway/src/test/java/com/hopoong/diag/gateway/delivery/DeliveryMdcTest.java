package com.hopoong.diag.gateway.delivery;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;

class DeliveryMdcTest {
    @Test void scopesIdentifiersAndRestoresCallerContextOnFailure() {
        MDC.put("requestId", "caller");
        try {
            try (var ignored = DeliveryMdc.open(new ClaimedEvent(1,"event","{}",2,1,"token"))) {
                assertThat(MDC.get("claimToken")).isEqualTo("token");
                assertThat(MDC.get("attemptNo")).isEqualTo("2");
            }
            assertThat(MDC.get("claimToken")).isNull();
            assertThat(MDC.get("outboxId")).isNull();
            assertThat(MDC.get("eventId")).isNull();
            assertThat(MDC.get("attemptNo")).isNull();
            assertThat(MDC.get("requestId")).isEqualTo("caller");
        } finally { MDC.clear(); }
    }
}
