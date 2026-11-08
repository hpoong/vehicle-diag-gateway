package com.hopoong.diag.gateway.production.application;
import com.hopoong.diag.gateway.production.persistence.VehiclePartRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class VehiclePartBatchValidatorTest {
    final VehiclePartBatchValidator validator=new VehiclePartBatchValidator();
    final OffsetDateTime t=OffsetDateTime.parse("2026-01-01T00:00:00Z");
    VehiclePartRow row(String vin,String serial,OffsetDateTime end) {return new VehiclePartRow(vin,"TCM","P",serial,"L",t,end);}
    @Test void collectsAllRulesInInputOrder() {
        var errors=validator.validate(List.of(row("v","A",null),row("v","A",null),row("v","B",t)));
        assertThat(errors).extracting(e -> e.getRule()).containsExactly("PART.DUPLICATE_PART_IN_BATCH","PART.MULTIPLE_CURRENT_PARTS","PART.INVALID_PART_PERIOD");
        assertThat(errors).extracting(e -> e.getField()).containsExactly("parts[1]","parts[1]","parts[2].removedAt");
    }
    @Test void allowsHistoryAndSerialInDifferentSlots() {
        assertThat(validator.validate(List.of(row("v","A",null),row("v","B",t.plusDays(1)),row("w","A",null)))).isEmpty();
    }
}
