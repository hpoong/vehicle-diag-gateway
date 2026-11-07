package com.hopoong.diag.receiver.quality.web;

import com.hopoong.diag.receiver.quality.application.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityEventControllerMdcTest {
    @Test
    void removesEventIdOnSuccessAndFailure() {
        var service = mock(QualityEventService.class);
        var controller = new QualityEventController(service);
        var request = new QualityEventRequest("id", null, null, null, null, null, null);
        when(service.apply(request)).thenAnswer(call -> {
            assertThat(MDC.get("eventId")).isEqualTo("id");
            return new ApplyResult(ApplyResult.Outcome.APPLIED, 0);
        });
        controller.apply(request);
        assertThat(MDC.get("eventId")).isNull();
        doThrow(new IllegalStateException()).when(service).apply(request);
        assertThatIllegalStateException().isThrownBy(() -> controller.apply(request));
        assertThat(MDC.get("eventId")).isNull();
    }
}
