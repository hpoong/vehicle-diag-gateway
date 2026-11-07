package com.hopoong.diag.receiver;

import com.hopoong.diag.receiver.fault.*;
import org.junit.jupiter.api.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FaultStateResetIT extends ReceiverDatabaseITSupport {
    @Test @Order(1) void changesState() { faultState.set(new FaultConfig(FaultMode.DOWN, 1)); }
    @Test @Order(2) void startsWithDefaultState() {
        assertThat(faultState.current()).isEqualTo(new FaultConfig(FaultMode.NONE, 15000));
    }
}
