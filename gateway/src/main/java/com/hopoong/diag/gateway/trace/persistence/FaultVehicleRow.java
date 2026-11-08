package com.hopoong.diag.gateway.trace.persistence;
import java.time.OffsetDateTime;
public record FaultVehicleRow(String vin, String serialNo, OffsetDateTime firstFaultAt) {}
