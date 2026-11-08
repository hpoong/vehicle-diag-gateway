package com.hopoong.diag.gateway.production.application;
import com.hopoong.diag.gateway.production.persistence.VehiclePartRow;
import com.hopoong.diag.gateway.production.web.*;
import com.hopoong.diag.websupport.error.BusinessException;
import static com.hopoong.diag.gateway.common.error.GatewayErrorCode.*;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Service
public class VehiclePartImportService {
    private static final Logger log=LoggerFactory.getLogger(VehiclePartImportService.class);
    private final VehiclePartWriter writer;
    private final VehiclePartBatchValidator validator;
    public VehiclePartImportService(VehiclePartWriter writer, VehiclePartBatchValidator validator) {
        this.writer=writer; this.validator=validator;
    }
    private OffsetDateTime time(String value) {
        return value==null ? null : OffsetDateTime.parse(value).truncatedTo(ChronoUnit.MICROS);
    }
    public VehiclePartImportResponse importParts(VehiclePartImportRequest req) {
        var rows=req.parts().stream().map(r -> new VehiclePartRow(r.vin(),r.ecuId(),r.partNo(),r.serialNo(),r.lotNo(),time(r.installedAt()),time(r.removedAt()))).toList();
        var errors=validator.validate(rows);
        if (!errors.isEmpty()) {
            log.info("PRODUCTION_REJECTED(part-batch-violation) violations={}",errors.size());
            throw new BusinessException(PART_BATCH_VIOLATION,errors);
        }
        var order=Comparator.comparing(VehiclePartRow::vin).thenComparing(VehiclePartRow::ecuId).thenComparing(VehiclePartRow::serialNo);
        var removed=rows.stream().filter(r -> r.removedAt()!=null).sorted(order).toList();
        var current=rows.stream().filter(r -> r.removedAt()==null).sorted(order).toList();
        UpsertCount count;
        try { count=writer.upsert(removed,current); }
        catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_vehicle_part_current")) {
                log.info("PRODUCTION_REJECTED(current-part-conflict)");
                throw new BusinessException(CURRENT_PART_CONFLICT);
            }
            throw e;
        }
        int unchanged=rows.size()-count.inserted()-count.updated();
        log.info("PRODUCTION_IMPORTED received={} inserted={} updated={} unchanged={}",rows.size(),count.inserted(),count.updated(),unchanged);
        return new VehiclePartImportResponse(req.batchId(),rows.size(),count.inserted(),count.updated(),unchanged);
    }
}
