package com.hopoong.diag.gateway.ingest;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EcuRequest(
        @NotNull @Size(min = 1, max = 30) @Pattern(regexp = "^[A-Z0-9_]+$") String ecuId,
        @Size(min = 1, max = 30) String swVersion,
        @NotNull @Size(max = 50) List<@NotNull @Pattern(regexp = "^[PCBU][0-3][0-9A-F]{3}$") String> dtcCodes) {}
