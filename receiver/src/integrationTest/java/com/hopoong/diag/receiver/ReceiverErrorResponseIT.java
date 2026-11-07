package com.hopoong.diag.receiver;

import com.hopoong.diag.testsupport.ErrorProbeController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "debug=false"})
@AutoConfigureMockMvc
@Import(ErrorProbeController.class)
class ReceiverErrorResponseIT {
    @Autowired MockMvc mvc;
    @Test
    void preservesRequestIdAndProblemContractForConflictAndUnavailable() throws Exception {
        for (String code : java.util.List.of("conflict", "unavailable")) {
            String id = UUID.randomUUID().toString();
            mvc.perform(get("/error-probe/" + code).header("X-Request-Id", id))
                    .andExpect(status().is(code.equals("conflict") ? 409 : 503))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string("X-Request-Id", id)).andExpect(jsonPath("$.requestId").value(id))
                    .andExpect(jsonPath("$.type").value("urn:diag-receiver:error:" + (code.equals("conflict") ? "event-conflict" : "service-unavailable")));
        }
    }
    @Test
    void rejectsValidationMalformedAndUnknownFieldsWithoutEchoingRejectedValues() throws Exception {
        mvc.perform(post("/error-probe/validate").contentType(MediaType.APPLICATION_JSON).content("{\"b\":\"abcd\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("a", "b")))
                .andExpect(content().string(not(containsString("abcd"))));
        for (String body : java.util.List.of("{", "{\"a\":\"x\",\"z\":1}")) {
            mvc.perform(post("/error-probe/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("urn:diag-receiver:error:invalid-request"));
        }
    }
}
