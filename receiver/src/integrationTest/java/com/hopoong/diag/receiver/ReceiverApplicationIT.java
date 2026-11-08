package com.hopoong.diag.receiver;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReceiverApplicationIT extends ReceiverDatabaseITSupport {
    @Test
    void migratesAllTablesAndExposesHealthyDatabase() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(2);
        for (String table : java.util.List.of("received_event", "quality_case", "quality_case_naive", "received_campaign", "received_campaign_vehicle")) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text", String.class, table)).isEqualTo(table);
        }
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
