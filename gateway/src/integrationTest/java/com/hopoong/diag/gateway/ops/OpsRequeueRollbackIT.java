package com.hopoong.diag.gateway.ops;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hopoong.diag.gateway.delivery.application.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.http.MediaType;

class OpsRequeueRollbackIT extends OpsITSupport {

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.hopoong.diag.gateway.ops.persistence.OpsMapper mapper;
    @Test void auditFailureRollsBackStateTransition() throws Exception {
        long id=fixture.createPending(1).get(0); fixture.markDead(id,"MAX_ATTEMPTS",3);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("audit failure"))
                .when(mapper).insertActionLog(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString());
        mvc.perform(post("/ops/events/{id}/requeue",id).header("X-Operator","kim.ops").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"receiver 점검 종료 확인 후 재처리\"}")).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForMap("SELECT status, cycle_no, attempt_count FROM outbox_event"))
                .containsEntry("status","DEAD").containsEntry("cycle_no",1).containsEntry("attempt_count",3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ops_action_log",Integer.class)).isZero();
    }
}
