package com.hopoong.diag.gateway.trace.persistence;
import org.apache.ibatis.annotations.*;
import java.time.OffsetDateTime;
import java.util.List;
@Mapper
public interface TraceMapper {
    List<LotBucketRow> aggregateLots(@Param("ecuId") String ecuId, @Param("dtcCode") String dtcCode, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
    List<FaultVehicleRow> findFaultVehicles(@Param("ecuId") String ecuId, @Param("dtcCode") String dtcCode, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, @Param("lotNo") String lotNo, @Param("limit") int limit);
    List<InstalledCountRow> countInstalledVehicles(@Param("ecuId") String ecuId, @Param("lotNos") List<String> lotNos);
    long countCurrentVehicles(@Param("ecuId") String ecuId, @Param("lotNo") String lotNo);
    long countExcludedRemoved(@Param("ecuId") String ecuId, @Param("lotNo") String lotNo);
    List<CurrentVehicleRow> findCurrentVehicles(@Param("ecuId") String ecuId, @Param("lotNo") String lotNo, @Param("limit") int limit);
}
