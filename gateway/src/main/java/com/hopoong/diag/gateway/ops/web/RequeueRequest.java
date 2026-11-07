package com.hopoong.diag.gateway.ops.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hopoong.diag.gateway.ingest.web.validation.StrictStringDeserializer;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

public record RequeueRequest(@NotBlank @JsonDeserialize(using = StrictStringDeserializer.class) String reason) {
    @AssertTrue(message = "reason must be 10~500 characters without NUL")
    public boolean isReasonValid() {
        return reason == null || (reason.codePointCount(0, reason.length()) >= 10
                && reason.codePointCount(0, reason.length()) <= 500 && reason.indexOf(0) < 0);
    }
}
