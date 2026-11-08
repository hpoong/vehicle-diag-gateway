package com.hopoong.diag.gateway.trace.application;
import com.hopoong.diag.gateway.trace.config.TraceProperties;
import com.hopoong.diag.gateway.trace.persistence.*;
import com.hopoong.diag.gateway.trace.web.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.List;
@Service
public class TraceService {
    private final TraceMapper mapper;
    private final TraceProperties props;
    public TraceService(TraceMapper mapper,TraceProperties props) {this.mapper=mapper;this.props=props;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ReverseTraceResponse reverse(String ecuId,String dtcCode,TraceWindow window,String lotNo) {
        var rows=mapper.aggregateLots(ecuId,dtcCode,window.from(),window.to());
        var lotNos=rows.stream().filter(r -> "LOT".equals(r.bucket())).map(LotBucketRow::lotNo).toList();
        var installed=lotNos.isEmpty()?List.<InstalledCountRow>of():mapper.countInstalledVehicles(ecuId,lotNos);
        var evidence=lotNo==null?null:mapper.findFaultVehicles(ecuId,dtcCode,window.from(),window.to(),lotNo,200);
        return ReverseTraceAssembler.assemble(ecuId,dtcCode,window,props.minSample(),rows,installed,lotNo,evidence);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ForwardTraceResponse forward(String lotNo,String ecuId,int limit) {
        long current=mapper.countCurrentVehicles(ecuId,lotNo);
        long excluded=mapper.countExcludedRemoved(ecuId,lotNo);
        var rows=current==0?List.<CurrentVehicleRow>of():mapper.findCurrentVehicles(ecuId,lotNo,limit);
        return ForwardTraceResponse.of(lotNo,ecuId,current,excluded,rows);
    }
}
