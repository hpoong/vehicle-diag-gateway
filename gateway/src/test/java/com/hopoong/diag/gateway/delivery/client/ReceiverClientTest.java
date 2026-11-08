package com.hopoong.diag.gateway.delivery.client;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import com.hopoong.diag.gateway.delivery.config.DeliveryProperties;

class ReceiverClientTest {
    @Test void refusesHttpInsideTransaction() {
        var p = new DeliveryProperties(false, Duration.ofMillis(500), 4, Duration.ofSeconds(30),
                Duration.ofSeconds(2), Duration.ofSeconds(5), 10,
                new DeliveryProperties.Backoff(Duration.ZERO, 2, Duration.ofMinutes(5)), "http://localhost:1", "test");
        var client = new ReceiverClient(p);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> client.send("DIAGNOSTIC_RESULT", "id", "token", "{} ")).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
