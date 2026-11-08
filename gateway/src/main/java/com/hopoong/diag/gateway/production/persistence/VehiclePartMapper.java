package com.hopoong.diag.gateway.production.persistence;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface VehiclePartMapper {
    List<Boolean> upsert(@Param("rows") List<VehiclePartRow> rows);
}
