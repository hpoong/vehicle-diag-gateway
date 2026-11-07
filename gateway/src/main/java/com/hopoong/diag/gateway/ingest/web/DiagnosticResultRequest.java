package com.hopoong.diag.gateway.ingest.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hopoong.diag.gateway.ingest.web.validation.IsoOffsetDateTime;
import com.hopoong.diag.gateway.ingest.web.validation.StrictStringDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DiagnosticResultRequest(
        @NotNull @Size(min = 1, max = 50) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String deviceId,
        @NotNull @Size(min = 1, max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String eventId,
        @NotNull @Size(min = 1, max = 20) @Pattern(regexp = "^[A-Z0-9_]+$") String brand,
        @NotNull @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$") String vin,
        @NotNull @Size(min = 1, max = 64) String sessionId,
        @NotNull @IsoOffsetDateTime @JsonDeserialize(using = StrictStringDeserializer.class) String occurredAt,
        @NotNull @Size(max = 30) List<@NotNull @Valid EcuRequest> ecus) {}
