package com.hopoong.diag.receiver;

import com.hopoong.diag.receiver.quality.persistence.QualityEventMapper;
import com.hopoong.diag.testsupport.QualityEventFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QualityEventRollbackIT extends ReceiverDatabaseITSupport {
    @MockitoSpyBean QualityEventMapper eventMapper;
    @Test
    void rollsBackWhenAffectedRowCountDoesNotMatch() {
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        doReturn(0).when(eventMapper).insertCases(anyString(), anyString(), any(), anyList());
        assertThat(post(body).getStatusCode().value()).isEqualTo(500);
        assertEmpty();
    }

    @Test
    void rollsBackEventAndAllowsSameRequestToRecover() {
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        doThrow(new IllegalStateException("injected insert failure")).when(eventMapper).insertCases(anyString(), anyString(), any(), anyList());
        assertThat(post(body).getStatusCode().value()).isEqualTo(500);
        assertEmpty();
        org.mockito.Mockito.reset(eventMapper);
        assertThat(post(body).getBody().at("/data/result").asText()).isEqualTo("APPLIED");
        assertThat(count("quality_case")).isEqualTo(3);
    }
}
