package com.hopoong.diag.receiver;

import com.hopoong.diag.receiver.fault.*;
import com.hopoong.diag.testsupport.QualityEventFixture;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("chaos")
@ExtendWith(OutputCaptureExtension.class)
class DecisionLogIT extends ReceiverDatabaseITSupport {
    private List<String> lines(CapturedOutput output, String id, String marker) {
        return output.getOut().lines().filter(s -> s.contains("requestId:" + id) && s.contains(marker)).toList();
    }
    @Test
    void logsExactlyOneDecisionWithRequestAndEventIdsAndNoPayload(CapturedOutput output) {
        String eventId = UUID.randomUUID().toString();
        String body = QualityEventFixture.sample(eventId);
        String requestId = UUID.randomUUID().toString();
        assertThat(postTo("/api/v1/quality-events", body, requestId).getStatusCode().value()).isEqualTo(200);
        assertThat(lines(output, requestId, "QUALITY_")).singleElement().asString()
                .contains("INFO", "eventId:" + eventId, "QUALITY_APPLIED caseCount=3");
        requestId = UUID.randomUUID().toString();
        postTo("/api/v1/quality-events", body, requestId);
        assertThat(lines(output, requestId, "QUALITY_")).singleElement().asString().contains("QUALITY_DUPLICATE caseCount=3");
        requestId = UUID.randomUUID().toString();
        assertThat(postTo("/api/v1/quality-events", body.replace("P0A80", "P0B00"), requestId).getStatusCode().value()).isEqualTo(409);
        assertThat(lines(output, requestId, "QUALITY_")).singleElement().asString().contains("WARN", "QUALITY_CONFLICT");
        assertThat(output.getAll()).doesNotContain("KMHXX00XXXX000001", "\"dtcCodes\"");
    }
    @Test
    void logsDownWithoutEventIdAndHoldAfterDecision(CapturedOutput output) {
        String body = QualityEventFixture.sample(UUID.randomUUID().toString());
        String id = UUID.randomUUID().toString();
        faultState.set(new FaultConfig(FaultMode.DOWN, 0));
        postTo("/api/v1/quality-events", body, id);
        assertThat(lines(output, id, "FAULT_APPLIED")).singleElement().asString().contains("mode=DOWN", "eventId:]");
        faultState.set(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 300));
        id = UUID.randomUUID().toString();
        assertThat(postTo("/api/v1/quality-events", body, id).getStatusCode().value()).isEqualTo(200);
        assertThat(lines(output, id, "FAULT_APPLIED")).singleElement().asString().contains("mode=DROP_AFTER_COMMIT holdMs=300");
        var correlated = lines(output, id, "caseCount=3");
        assertThat(correlated).hasSize(1);
        assertThat(output.getOut().indexOf(correlated.get(0))).isLessThan(output.getOut().indexOf(lines(output, id, "FAULT_APPLIED").get(0)));
    }
}
