package com.hopoong.diag.gateway.scenario;

import org.junit.jupiter.api.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.receiver.fault.FaultMode;
import com.hopoong.diag.gateway.delivery.application.RecordResult;
import com.hopoong.diag.gateway.delivery.persistence.ClaimedEvent;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class RequeueScenarioIT extends ScenarioITSupport {
    static ReceiverRunner RECEIVER;
    @BeforeAll static void startReceiver() { RECEIVER = ReceiverRunner.start(false); }
    @AfterAll static void stopReceiver() { if (RECEIVER != null) RECEIVER.close(); }
    @DynamicPropertySource static void receiverUrl(DynamicPropertyRegistry r) { r.add("gateway.delivery.receiver-url", () -> RECEIVER.url()); }
    @Override protected ReceiverRunner receiver() { return RECEIVER; }

    @Test void s10_maxAttemptsThenOperatorRequeuesAndDeliveryRecovers() throws Exception {
        var ids=fixture.createPending(5); fault(FaultMode.DOWN);
        for(int i=0;i<3;i++) { pullNextAttempt(); assertThat(delivery.runOnce().failed()).isZero(); }
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE status='DEAD' AND dead_reason='MAX_ATTEMPTS'",Integer.class)).isEqualTo(5);
        mvc.perform(get("/ops/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.data.dead.MAX_ATTEMPTS").value(5));
        mvc.perform(get("/ops/events").param("status","DEAD")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(5));
        fault(FaultMode.NONE);
        for(long id:ids) mvc.perform(post("/ops/events/{id}/requeue",id).header("X-Operator","scenario.ops")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"receiver 점검 종료 확인 후 재처리\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cycleNo").value(2));
        assertThat(delivery.runOnce().sent()).isEqualTo(5);
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM ops_action_log",Integer.class)).isEqualTo(5);
        assertThat(gatewayJdbc.queryForObject("SELECT count(*) FROM delivery_attempt WHERE cycle_no=2 AND attempt_no=1 AND outcome='SUCCESS'",Integer.class)).isEqualTo(5);
        assertStandard("S10",5);
    }
}
