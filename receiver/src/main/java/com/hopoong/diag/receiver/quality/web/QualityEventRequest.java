package com.hopoong.diag.receiver.quality.web;

import com.hopoong.diag.receiver.quality.web.validation.IsoOffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record QualityEventRequest(
        @NotNull @Pattern(regexp = "^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$") String eventId,
        @NotNull @Valid SourceRequest source,
        @NotBlank @Size(max = 17) String vin,
        String brand,
        @NotNull @IsoOffsetDateTime String occurredAt,
        @IsoOffsetDateTime String receivedAt,
        @NotNull List<@NotNull @Valid EcuRequest> ecus) {}
