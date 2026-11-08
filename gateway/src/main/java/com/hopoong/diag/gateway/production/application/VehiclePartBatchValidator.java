package com.hopoong.diag.gateway.production.application;
import com.hopoong.diag.gateway.production.persistence.VehiclePartRow;
import com.hopoong.diag.websupport.error.ApiFieldError;
import java.util.*;
import org.springframework.stereotype.Component;
@Component
public class VehiclePartBatchValidator {
    public List<ApiFieldError> validate(List<VehiclePartRow> rows) {
        var errors = new ArrayList<ApiFieldError>();
        var keys = new HashMap<List<String>, Integer>();
        var slots = new HashMap<List<String>, Integer>();
        for (int i=0; i<rows.size(); i++) {
            var r=rows.get(i);
            String field="parts["+i+"]";
            if (r.removedAt()!=null && !r.removedAt().isAfter(r.installedAt()))
                errors.add(new ApiFieldError(field+".removedAt", "PART.INVALID_PART_PERIOD", "탈거 시각은 장착 시각 이후여야 합니다."));
            var previous=keys.putIfAbsent(List.of(r.vin(),r.ecuId(),r.serialNo()),i);
            if (previous!=null) errors.add(new ApiFieldError(field,"PART.DUPLICATE_PART_IN_BATCH","parts["+previous+"]와 같은 장착 키입니다"));
            if (r.removedAt()==null) {
                previous=slots.putIfAbsent(List.of(r.vin(),r.ecuId()),i);
                if (previous!=null) errors.add(new ApiFieldError(field,"PART.MULTIPLE_CURRENT_PARTS","parts["+previous+"]와 같은 장착 자리의 현재 장착 행입니다"));
            }
        }
        return errors;
    }
}
