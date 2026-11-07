package com.hopoong.diag.gateway.ops.web;

import java.time.Instant;
import java.util.Map;

public record OpsSummaryResponse(Instant asOf, Map<String, Long> counts, Long oldestPendingAgeSeconds,
        Map<String, Long> dead) {}
