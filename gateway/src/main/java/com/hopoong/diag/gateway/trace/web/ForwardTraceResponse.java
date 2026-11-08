package com.hopoong.diag.gateway.trace.web;
import com.hopoong.diag.gateway.trace.persistence.CurrentVehicleRow;
import java.time.Instant;
import java.util.List;
public record ForwardTraceResponse(String lotNo,String ecuId,long currentVehicles,long excludedRemoved,boolean truncated,List<Vehicle> vehicles) {
    public record Vehicle(String vin,String serialNo,Instant installedAt,Instant latestDiagAt,List<String> latestDtcCodes) {}
    public static ForwardTraceResponse of(String lotNo,String ecuId,long current,long excluded,List<CurrentVehicleRow> rows) {
        return new ForwardTraceResponse(lotNo,ecuId,current,excluded,current>rows.size(),rows.stream().map(r -> new Vehicle(r.vin(),r.serialNo(),r.installedAt().toInstant(),
            r.latestDiagAt()==null?null:r.latestDiagAt().toInstant(),r.latestDtcCodes()==null?null:List.of(r.latestDtcCodes()))).toList());
    }
}
