package com.hopoong.diag.gateway.production.web;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.hopoong.diag.gateway.ingest.web.validation.StrictStringDeserializer;
import com.hopoong.diag.gateway.ingest.web.validation.IsoOffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record VehiclePartRequest(
        @NotNull @Pattern(regexp="^[A-HJ-NPR-Z0-9]{17}$") @JsonDeserialize(using = StrictStringDeserializer.class) String vin,
        @NotNull @Size(min=1,max=30) @Pattern(regexp="^[A-Z0-9_]+$") @JsonDeserialize(using = StrictStringDeserializer.class) String ecuId,
        @NotNull @Size(min=1,max=30) @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9._-]*$") @JsonDeserialize(using = StrictStringDeserializer.class) String partNo,
        @NotNull @Size(min=1,max=40) @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9._-]*$") @JsonDeserialize(using = StrictStringDeserializer.class) String serialNo,
        @Size(min=1,max=30) @Pattern(regexp="^[A-Za-z0-9][A-Za-z0-9._-]*$") @JsonDeserialize(using = StrictStringDeserializer.class) String lotNo,
        @NotNull @IsoOffsetDateTime @JsonDeserialize(using = StrictStringDeserializer.class) String installedAt,
        @IsoOffsetDateTime @JsonDeserialize(using = StrictStringDeserializer.class) String removedAt) {}
