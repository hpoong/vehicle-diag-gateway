package com.hopoong.diag.receiver;
import com.hopoong.diag.receiver.fault.*;
import com.hopoong.diag.testsupport.CampaignTargetFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
@ActiveProfiles("chaos")
class CampaignTargetFaultIT extends ReceiverDatabaseITSupport {
    @Test void downRejectsBeforeParsingAndNoneRecovers() {
        String body=CampaignTargetFixture.sample(UUID.randomUUID().toString(),3);
        faultState.set(new FaultConfig(FaultMode.DOWN,2000));
        for (String input:new String[]{body,"{"}) assertThat(postTo("/api/v1/campaign-targets",input,null).getStatusCode().value()).isEqualTo(503);
        assertEmpty();faultState.set(new FaultConfig(FaultMode.NONE,2000));
        assertThat(postTo("/api/v1/campaign-targets",body,null).getBody().at("/data/result").asText()).isEqualTo("APPLIED");
    }
}
