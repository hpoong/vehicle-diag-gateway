package com.hopoong.diag.receiver.quality.persistence;

import com.hopoong.diag.receiver.quality.application.CaseRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QualityEventMapper {
    Optional<String> insertReceived(@Param("eventId") String eventId, @Param("payloadHash") String payloadHash,
            @Param("sourceResultId") long sourceResultId, @Param("vin") String vin, @Param("caseCount") int caseCount);
    Optional<ReceivedEventRow> findReceived(@Param("eventId") String eventId);
    int insertCases(@Param("eventId") String eventId, @Param("vin") String vin,
            @Param("occurredAt") OffsetDateTime occurredAt, @Param("cases") List<CaseRow> cases);
    int insertNaiveCases(@Param("eventId") String eventId, @Param("cases") List<CaseRow> cases);
}
