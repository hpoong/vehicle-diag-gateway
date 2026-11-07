package com.hopoong.diag.receiver.fault;

import com.hopoong.diag.websupport.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("chaos")
@RestController
@RequestMapping("/admin/faults")
@RequiredArgsConstructor
public class FaultController {
    private final FaultState state;

    @PostMapping
    public ResponseEntity<ApiResponse<FaultConfig>> set(@Valid @RequestBody FaultRequest request) {
        var config = new FaultConfig(request.mode(),
                request.holdMs() == null ? FaultConfig.DEFAULT_HOLD_MS : request.holdMs());
        state.set(config);
        return ResponseEntity.ok(ApiResponse.success(config));
    }
}
