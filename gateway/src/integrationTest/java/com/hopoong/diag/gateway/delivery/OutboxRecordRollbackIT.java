package com.hopoong.diag.gateway.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.hopoong.diag.gateway.delivery.application.AttemptOutcome;
import com.hopoong.diag.gateway.delivery.persistence.DeliveryAttemptMapper;

class OutboxRecordRollbackIT extends DeliveryDatabaseITSupport {
    @MockitoSpyBean DeliveryAttemptMapper attempts;
    @Test void missingStartedAttemptRollsBackOutboxUpdate() {
        var event = claimOne();
        doReturn(0).when(attempts).finish(anyString(), anyString(), any(), any(), any(), anyLong());
        assertThatThrownBy(() -> recorder.record(event, outcome(AttemptOutcome.SUCCESS))).isInstanceOf(IllegalStateException.class);
        assertThat(text("status")).isEqualTo("PENDING");
        assertThat(text("claim_token::text")).isEqualTo(event.claimToken());
        assertThat(text("sent_at::text")).isNull();
        assertThat(outcomes()).containsExactly("STARTED");
    }
}
