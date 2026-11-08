package com.hopoong.diag.gateway.trace.application;
import com.hopoong.diag.gateway.trace.web.ReverseTraceResponse;
import com.hopoong.diag.gateway.trace.web.ReverseTraceResponse.*;
import com.hopoong.diag.gateway.trace.persistence.*;
import java.util.*;
import java.math.*;
public final class ReverseTraceAssembler {
    private ReverseTraceAssembler() {}
    private static double rate(long f,long d) {return BigDecimal.valueOf(f).divide(BigDecimal.valueOf(d),4,RoundingMode.HALF_UP).doubleValue();}
    public static ReverseTraceResponse assemble(String ecuId,String dtcCode,TraceWindow window,int minSample,
            List<LotBucketRow> rows,List<InstalledCountRow> installed,String lotNo,List<FaultVehicleRow> evidence) {
        var lotRows=rows.stream().filter(r -> "LOT".equals(r.bucket())).toList();
        long sf=lotRows.stream().mapToLong(LotBucketRow::faultVehicles).sum();
        long sd=lotRows.stream().mapToLong(LotBucketRow::diagnosedVehicles).sum();
        var counts=new HashMap<String,Long>();
        installed.forEach(r -> counts.put(r.lotNo(),r.installedVehicles()));
        var lots=new ArrayList<LotStat>();
        for (var r:lotRows) lots.add(new LotStat(r.lotNo(),r.faultVehicles(),r.diagnosedVehicles(),counts.getOrDefault(r.lotNo(),0L),
            rate(r.faultVehicles(),r.diagnosedVehicles()),sd==r.diagnosedVehicles()?null:rate(sf-r.faultVehicles(),sd-r.diagnosedVehicles()),r.diagnosedVehicles()<minSample));
        lots.sort((a,b) -> {
            int c=Boolean.compare(a.lowSample(),b.lowSample());
            if (c==0) c=Long.compare(b.faultVehicles()*a.diagnosedVehicles(),a.faultVehicles()*b.diagnosedVehicles());
            return c==0 ? a.lotNo().compareTo(b.lotNo()) : c;
        });
        var buckets=new HashMap<String,BucketCount>();
        rows.forEach(r -> buckets.put(r.bucket(),new BucketCount(r.faultVehicles(),r.diagnosedVehicles())));
        var zero=new BucketCount(0,0);
        var unattributed=new Unattributed(buckets.getOrDefault("UNKNOWN_LOT",zero),buckets.getOrDefault("NO_HISTORY",zero),buckets.getOrDefault("AMBIGUOUS",zero));
        Evidence proof=null;
        if (lotNo!=null) {
            var vehicles=evidence.stream().map(r -> new FaultVehicle(r.vin(),r.serialNo(),r.firstFaultAt().toInstant())).toList();
            long f=lotRows.stream().filter(r -> lotNo.equals(r.lotNo())).mapToLong(LotBucketRow::faultVehicles).sum();
            proof=new Evidence(lotNo,f>vehicles.size(),vehicles);
        }
        return new ReverseTraceResponse(ecuId,dtcCode,window.from().toInstant(),window.to().toInstant(),minSample,
            new Totals(rows.stream().mapToLong(LotBucketRow::faultVehicles).sum(),rows.stream().mapToLong(LotBucketRow::diagnosedVehicles).sum()),lots,unattributed,proof,
            List.of("고장률 = 고장 차량 / 기간 내 같은 ECU 진단 차량","LOT 귀속: 고장 차량은 첫 고장 진단 시각, 그 외 차량은 기간 내 마지막 진단 시각의 장착 부품","원인 확정이 아닌 원인 후보입니다"));
    }
}
