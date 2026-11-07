package com.hopoong.diag.receiver.fault;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class FaultState {
    private final AtomicReference<FaultConfig> ref = new AtomicReference<>(
            new FaultConfig(FaultMode.NONE, FaultConfig.DEFAULT_HOLD_MS));

    public FaultConfig current() { return ref.get(); }
    public void set(FaultConfig config) { ref.set(Objects.requireNonNull(config)); }

    public void awaitIfDropAfterCommit() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Fault hold must run after commit");
        }
        var config = current();
        if (config.mode() != FaultMode.DROP_AFTER_COMMIT) return;
        log.info("FAULT_APPLIED mode=DROP_AFTER_COMMIT holdMs={}", config.holdMs());
        try {
            Thread.sleep(config.holdMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
