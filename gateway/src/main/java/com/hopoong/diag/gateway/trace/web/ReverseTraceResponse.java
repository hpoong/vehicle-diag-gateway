package com.hopoong.diag.gateway.trace.web;
import java.time.Instant;
import java.util.List;
public record ReverseTraceResponse(String ecuId, String dtcCode, Instant from, Instant to, int minSample,
        Totals totals, List<LotStat> lots, Unattributed unattributed, Evidence evidence, List<String> notes) {
    public record Totals(long faultVehicles, long diagnosedVehicles) {}
    public record BucketCount(long faultVehicles, long diagnosedVehicles) {}
    public record Unattributed(BucketCount unknownLot, BucketCount noHistory, BucketCount ambiguous) {}
    public record LotStat(String lotNo, long faultVehicles, long diagnosedVehicles, long installedVehicles,
                          double faultRate, Double otherLotsFaultRate, boolean lowSample) {}
    public record Evidence(String lotNo, boolean truncated, List<FaultVehicle> vehicles) {}
    public record FaultVehicle(String vin, String serialNo, Instant firstFaultAt) {}
}
