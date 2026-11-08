package com.hopoong.diag.gateway.production.web;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hopoong.diag.gateway.ingest.web.validation.StrictStringDeserializer;
import com.hopoong.diag.gateway.ingest.web.validation.IsoOffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record VehiclePartImportRequest(
        @NotNull @Size(min=1,max=30) @Pattern(regexp="^[A-Za-z0-9_-]+$") @JsonDeserialize(using = StrictStringDeserializer.class) String source,
        @NotNull @Size(min=1,max=64) @Pattern(regexp="^[A-Za-z0-9_-]+$") @JsonDeserialize(using = StrictStringDeserializer.class) String batchId,
        @NotNull @Size(min=1,max=1000) List<@NotNull @Valid VehiclePartRequest> parts) {}
