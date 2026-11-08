package com.hopoong.diag.gateway.trace.persistence;
import java.time.OffsetDateTime;
public record LotBucketRow(String bucket, String lotNo, long faultVehicles, long diagnosedVehicles) {}
