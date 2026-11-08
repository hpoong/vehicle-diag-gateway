package com.hopoong.diag.gateway.production;
import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import static org.assertj.core.api.Assertions.*;
class VehiclePartSchemaIT extends ProductionITSupport {
    void insert(String serial,String lot,String removed) {
        jdbc.update("INSERT INTO vehicle_part(vin,ecu_id,part_no,serial_no,lot_no,installed_at,removed_at) VALUES ('KMHXX00XXXX000001','TCM','P',?,?, '2026-01-01'::timestamptz,?::timestamptz)",serial,lot,removed);
    }
    @Test void schemaAndIndexes() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='vehicle_part'",Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename='vehicle_part'",String.class))
            .contains("uq_vehicle_part_slot_serial","uq_vehicle_part_current","ix_vehicle_part_lot").doesNotContain("ix_vehicle_part_slot");
    }
    @Test void partialIndexesHaveTheRequiredPredicates() {
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='uq_vehicle_part_current'",String.class)).contains("(vin, ecu_id)","WHERE (removed_at IS NULL)");
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname='ix_vehicle_part_lot'",String.class)).contains("(ecu_id, lot_no)","WHERE (lot_no IS NOT NULL)");
    }
    @Test void slotAndSerialAreUnique() {
        insert("A","L","2026-02-01");
        assertThatThrownBy(() -> insert("A","L","2026-03-01")).isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_vehicle_part_slot_serial");
    }
    @Test void onlyOneCurrentPartPerSlot() {
        insert("A","L",null);
        assertThatThrownBy(() -> insert("B","L",null)).isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uq_vehicle_part_current");
        insert("C","L","2026-02-01");
        jdbc.update("INSERT INTO vehicle_part(vin,ecu_id,part_no,serial_no,installed_at) VALUES ('KMHXX00XXXX000001','BMS','P','D',now())");
    }
    @Test void periodAndLotConstraints() {
        for (String removed: new String[]{"2026-01-01","2025-12-31"})
            assertThatThrownBy(() -> insert("A","L",removed)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_vehicle_part_period");
        assertThatThrownBy(() -> insert("A","",null)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_vehicle_part_lot_no");
        insert("A",null,"2026-01-01T00:00:00.000001Z");
    }
}
