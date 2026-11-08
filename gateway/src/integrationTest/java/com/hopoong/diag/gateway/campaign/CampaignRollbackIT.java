package com.hopoong.diag.gateway.campaign;
import com.hopoong.diag.testsupport.CampaignFixture;
import com.hopoong.diag.gateway.delivery.persistence.OutboxMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CampaignRollbackIT extends CampaignITSupport {
    @MockitoSpyBean OutboxMapper outbox;
    @Test void outboxFailureRollsBackCampaignAndSnapshot() throws Exception {
        var request=CampaignFixture.request("r1","TCM","L-A");
        doThrow(new IllegalStateException("injected failure")).when(outbox).insertCampaign(anyLong(),anyString(),anyString());
        create(request).andExpect(status().isInternalServerError());
        for (String table:List.of("campaign","campaign_vehicle","outbox_event")) assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isZero();
        org.mockito.Mockito.reset(outbox);create(request).andExpect(status().isAccepted());
    }
}
