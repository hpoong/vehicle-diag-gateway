package com.hopoong.diag.receiver.quality.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EcuRequest(@NotBlank @Size(max = 30) String ecuId,
        @Size(max = 30) String swVersion,
        @NotNull List<@NotBlank @Size(max = 10) String> dtcCodes) {}
