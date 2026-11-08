package com.hopoong.diag.gateway.production.persistence;
import java.time.OffsetDateTime;
public record VehiclePartRow(String vin, String ecuId, String partNo, String serialNo, String lotNo,
                             OffsetDateTime installedAt, OffsetDateTime removedAt) {}
