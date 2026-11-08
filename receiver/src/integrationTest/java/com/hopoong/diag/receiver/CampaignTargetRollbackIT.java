package com.hopoong.diag.receiver;
import com.hopoong.diag.receiver.campaign.persistence.CampaignTargetMapper;
import com.hopoong.diag.testsupport.CampaignTargetFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CampaignTargetRollbackIT extends ReceiverDatabaseITSupport {
    @MockitoSpyBean CampaignTargetMapper campaignMapper;
    @Test void rollsBackAndCanRecover() {
        String body=CampaignTargetFixture.sample(UUID.randomUUID().toString(),3);
        doThrow(new IllegalStateException("injected failure")).when(campaignMapper).insertVehicles(anyString(),anyList());
        assertThat(postTo("/api/v1/campaign-targets",body,null).getStatusCode().value()).isEqualTo(500);assertEmpty();
        org.mockito.Mockito.reset(campaignMapper);
        assertThat(postTo("/api/v1/campaign-targets",body,null).getBody().at("/data/result").asText()).isEqualTo("APPLIED");
        assertThat(count("received_campaign_vehicle")).isEqualTo(3);
    }
}
