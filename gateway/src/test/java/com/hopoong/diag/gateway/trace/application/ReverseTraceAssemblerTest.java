package com.hopoong.diag.gateway.trace.application;
import com.hopoong.diag.gateway.trace.persistence.LotBucketRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ReverseTraceAssemblerTest {
    @Test void roundingSingleLotAndTies() {
        var window=new TraceWindow(OffsetDateTime.parse("2026-10-01T00:00:00Z"),OffsetDateTime.parse("2026-11-01T00:00:00Z"));
        var result=ReverseTraceAssembler.assemble("TCM","P0700",window,4,List.of(new LotBucketRow("LOT","A",5,6)),List.of(),null,null);
        assertThat(result.lots().get(0).faultRate()).isEqualTo(0.8333);
        assertThat(result.lots().get(0).otherLotsFaultRate()).isNull();
        result=ReverseTraceAssembler.assemble("TCM","P0700",window,4,List.of(new LotBucketRow("LOT","B",5,6),new LotBucketRow("LOT","A",5,6)),List.of(),null,null);
        assertThat(result.lots()).extracting(l -> l.lotNo()).containsExactly("A","B");
    }
}
