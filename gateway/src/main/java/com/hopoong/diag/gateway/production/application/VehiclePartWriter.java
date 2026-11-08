package com.hopoong.diag.gateway.production.application;
import com.hopoong.diag.gateway.production.persistence.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class VehiclePartWriter {
    private final VehiclePartMapper mapper;
    public VehiclePartWriter(VehiclePartMapper mapper) { this.mapper = mapper; }
    @Transactional(rollbackFor = Exception.class)
    public UpsertCount upsert(List<VehiclePartRow> removed, List<VehiclePartRow> current) {
        var flags = new ArrayList<Boolean>();
        if (!removed.isEmpty()) flags.addAll(mapper.upsert(removed));
        if (!current.isEmpty()) flags.addAll(mapper.upsert(current));
        int inserted = (int) flags.stream().filter(Boolean::booleanValue).count();
        return new UpsertCount(inserted, flags.size() - inserted);
    }
}
