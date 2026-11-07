package com.hopoong.diag.receiver;

import com.hopoong.diag.receiver.fault.*;
import com.hopoong.diag.testsupport.QualityEventFixture;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.*;

class FaultDisabledIT extends ReceiverDatabaseITSupport {
    @Autowired ApplicationContext context;
    @Test
    void adminAndInterceptorAreAbsentWithoutChaos() {
        assertThat(context.getBeansOfType(FaultController.class)).isEmpty();
        assertThat(context.getBeansOfType(FaultWebConfig.class)).isEmpty();
        var response = postTo("/admin/faults", "{\"mode\":\"DOWN\"}", null);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().path("type").asText()).isEqualTo("urn:diag-receiver:error:not-found");
        assertThat(faultState.current().mode()).isEqualTo(FaultMode.NONE);
        faultState.set(new FaultConfig(FaultMode.DOWN, 0));
        assertThat(post(QualityEventFixture.sample(UUID.randomUUID().toString())).getStatusCode().value()).isEqualTo(200);
    }
}
