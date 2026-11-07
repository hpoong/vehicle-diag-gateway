package com.hopoong.diag.testsupport;

import com.hopoong.diag.receiver.common.error.ReceiverErrorCode;
import com.hopoong.diag.websupport.error.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/error-probe")
public class ErrorProbeController {
    public record ProbeRequest(@NotNull String a, @Size(max = 3) String b) {}
    @GetMapping("/conflict") public void conflict() { throw new BusinessException(ReceiverErrorCode.EVENT_CONFLICT); }
    @GetMapping("/unavailable") public void unavailable() { throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE); }
    @PostMapping("/validate") public void validate(@Valid @RequestBody ProbeRequest request) {}
}
