package com.hopoong.diag.gateway.ingest;

import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DiagnosticResultMapper {
    Optional<ExistingResult> findByKey(@Param("deviceId") String deviceId, @Param("eventId") String eventId);
}
