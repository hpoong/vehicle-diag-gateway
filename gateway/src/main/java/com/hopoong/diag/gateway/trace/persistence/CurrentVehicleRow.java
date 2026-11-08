package com.hopoong.diag.gateway.trace.persistence;
import java.time.OffsetDateTime;
public record CurrentVehicleRow(String vin, String serialNo, OffsetDateTime installedAt, OffsetDateTime latestDiagAt, String[] latestDtcCodes) {}
