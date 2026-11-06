package com.hopoong.diag.gateway;

import com.hopoong.diag.testsupport.ErrorProbeController;
import com.hopoong.diag.websupport.web.RequestIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false"})
@AutoConfigureMockMvc
@Import(ErrorProbeController.class)
class ErrorResponseIT {
    @Autowired MockMvc mvc;

    @Test
    void businessErrorsAndMissingPathUseProblemDetails() throws Exception {
        String[][] cases = {{"/error-probe/conflict", "409", "event-conflict"},
                {"/error-probe/rules", "422", "common-rule-violation"},
                {"/error-probe/policy", "422", "policy-violation"},
                {"/error-probe/missing", "404", "not-found"}, {"/no-such-path", "404", "not-found"}};
        for (String[] item : cases) {
            String id = UUID.randomUUID().toString();
            mvc.perform(get(item[0]).header(RequestIdFilter.HEADER, id))
                    .andExpect(status().is(Integer.parseInt(item[1])))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string(RequestIdFilter.HEADER, id))
                    .andExpect(jsonPath("$.requestId").value(id))
                    .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:" + item[2]));
        }
        mvc.perform(get("/error-probe/rules")).andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(get("/error-probe/policy")).andExpect(jsonPath("$.policyCode").value("X"));
    }

    @Test
    void databaseFailuresReturnServiceUnavailableWithoutInternalDetails() throws Exception {
        for (String kind : new String[]{"transaction", "mybatis"}) {
            var result = mvc.perform(get("/error-probe/" + kind))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:diag-gateway:error:service-unavailable"))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-down");
            assertThat(result.getResponse().getContentAsString())
                    .contains(result.getResponse().getHeader(RequestIdFilter.HEADER));
        }
    }
}
