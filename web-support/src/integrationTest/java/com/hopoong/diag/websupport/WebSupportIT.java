package com.hopoong.diag.websupport;

import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import com.hopoong.diag.websupport.response.ApiResponse;
import com.hopoong.diag.websupport.web.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = WebSupportIT.Application.class, properties = {
        "diag.web.problem-type-prefix=urn:test:error:",
        "spring.jackson.deserialization.fail-on-unknown-properties=true",
        "spring.mvc.problemdetails.enabled=true"
})
@AutoConfigureMockMvc
class WebSupportIT {
    @Autowired
    MockMvc mvc;

    @Test
    void shouldPreserveAcceptedStatusAndRequestId_whenSuccessful() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/test").header(RequestIdFilter.HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"test\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(RequestIdFilter.HEADER, requestId))
                .andExpect(jsonPath("$.data.name").value("test"));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldReturnFieldErrorsAndCorrelatedRequestId_whenBodyInvalid() throws Exception {
        String requestId = UUID.randomUUID().toString();
        mvc.perform(post("/test").header(RequestIdFilter.HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:test:error:invalid-request"))
                .andExpect(jsonPath("$.instance").value("/test"))
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].rule").value("NotBlank"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldRejectMalformedAndUnknownJson_whenDeserializationFails() throws Exception {
        for (String body : new String[]{"{", "{\"name\":\"test\",\"unknown\":true}"}) {
            mvc.perform(post("/test").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("urn:test:error:invalid-request"))
                    .andExpect(jsonPath("$.detail").doesNotExist());
        }
    }

    @Test
    void shouldReturnBadRequest_whenParameterValidationFails() throws Exception {
        mvc.perform(get("/test/count").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").exists());
        mvc.perform(get("/test/count").param("count", "text"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/test/count"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldPreserveFrameworkStatusAndHeaders_whenRoutingFails() throws Exception {
        mvc.perform(get("/test"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.status").value(405));
        mvc.perform(get("/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:test:error:not-found"));
        mvc.perform(post("/test").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void shouldMapBusinessException_whenResourceMissing() throws Exception {
        mvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:test:error:not-found"));
    }

    @Test
    void shouldHideInternalDetailsAndReplaceInvalidRequestId_whenUnexpectedException() throws Exception {
        var result = mvc.perform(get("/test/unexpected").header(RequestIdFilter.HEADER, "invalid-id"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("urn:test:error:internal-server-error"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andReturn();
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(result.getResponse().getContentAsString()).contains(requestId).doesNotContain("secret-db-password");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import(TestController.class)
    static class Application {
    }

    @RestController
    static class TestController {
        @PostMapping("/test")
        ResponseEntity<ApiResponse<TestRequest>> create(@Valid @RequestBody TestRequest request) {
            return ResponseEntity.accepted().body(ApiResponse.success(request));
        }

        @GetMapping("/test/count")
        ApiResponse<Integer> count(@RequestParam("count") @Min(1) int count) {
            return ApiResponse.success(count);
        }

        @GetMapping("/test/business")
        void business() {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }

        @GetMapping("/test/unexpected")
        void unexpected() {
            throw new IllegalStateException("secret-db-password");
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
