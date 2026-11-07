package com.hopoong.diag.receiver.fault;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class FaultStateTest {
    @Test
    void defaultsAndHoldOutsideTransaction() {
        var state = new FaultState();
        assertThat(state.current()).isEqualTo(new FaultConfig(FaultMode.NONE, 15_000));
        state.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 50));
        long start = System.nanoTime();
        state.awaitIfDropAfterCommit();
        assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(50_000_000);
    }

    @Test
    void refusesEvenNoneInsideTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatIllegalStateException().isThrownBy(new FaultState()::awaitIfDropAfterCommit);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void restoresInterruptFlag() {
        var state = new FaultState();
        state.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 50));
        Thread.currentThread().interrupt();
        try {
            state.awaitIfDropAfterCommit();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
