package com.hopoong.diag.receiver.fault;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record FaultRequest(@NotNull FaultMode mode, @PositiveOrZero @Max(60_000) Long holdMs) {}
