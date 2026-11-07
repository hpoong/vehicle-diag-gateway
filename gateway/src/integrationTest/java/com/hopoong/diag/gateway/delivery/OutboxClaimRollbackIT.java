package com.hopoong.diag.gateway.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OutboxClaimRollbackIT extends DeliveryDatabaseITSupport {
    @MockitoBean DeliveryAttemptMapper attempts;
    @Test void attemptInsertFailureRollsBackClaimAndCounter() {
        fixture.createPending(1);
        doThrow(new IllegalStateException("insert failure")).when(attempts).insertStarted(anyLong(), anyInt(), anyInt(), anyString(), anyString());
        assertThatThrownBy(() -> claimer.claim(1)).isInstanceOf(IllegalStateException.class);
        assertThat(text("claim_token::text")).isNull();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_event", Integer.class)).isZero();
        assertThat(outcomes()).isEmpty();
    }
}
