package com.hopoong.diag.receiver;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.*;

class ReceiverSchemaIT extends ReceiverDatabaseITSupport {
    private void received(String id, String hash, int cases) {
        jdbc.update("INSERT INTO received_event(event_id,payload_hash,source_result_id,vin,case_count) VALUES (?::uuid,?,1,'VIN',?)", id, hash, cases);
    }
    private void quality(String id) {
        jdbc.update("INSERT INTO quality_case(event_id,vin,ecu_id,dtc_code,occurred_at) VALUES (?::uuid,'VIN','BMS','P0A80',now())", id);
    }
    @Test
    void enforcesForeignKeyUniqueCaseCountAndHashConstraints() {
        String id = UUID.randomUUID().toString();
        assertThatThrownBy(() -> quality(id)).isInstanceOf(DataIntegrityViolationException.class);
        for (String hash : java.util.List.of("a".repeat(63), "A".repeat(64))) {
            assertThatThrownBy(() -> received(id, hash, 0)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> received(id, "a".repeat(64), -1)).isInstanceOf(DataIntegrityViolationException.class);
        received(id, "a".repeat(64), 1);
        quality(id);
        assertThatThrownBy(() -> quality(id)).isInstanceOf(DataIntegrityViolationException.class);
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO quality_case_naive(event_id,ecu_id,dtc_code) VALUES (?::uuid,'BMS','P0A80')", id);
        }
        assertThat(count("quality_case_naive")).isEqualTo(2);
    }
}
