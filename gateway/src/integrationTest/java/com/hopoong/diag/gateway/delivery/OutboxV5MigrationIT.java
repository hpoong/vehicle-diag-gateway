package com.hopoong.diag.gateway.delivery;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class OutboxV5MigrationIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void upgradesExistingDiagnosticOutboxWithoutChangingItsMeaning() {
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).target("4").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        long result=jdbc.queryForObject("INSERT INTO diagnostic_result(device_id,event_id,payload_hash,brand,vin,session_id,occurred_at,policy_code,raw_payload) VALUES ('d','e',repeat('a',64),'ZETA','KMHXX00XXXX000001','s',now(),'STANDARD-v1','{}') RETURNING id",Long.class);
        jdbc.update("INSERT INTO outbox_event(event_id,result_id,payload) SELECT id,?,jsonb_build_object('eventId',id::text) FROM (SELECT gen_random_uuid() id) e",result);
        Flyway.configure().dataSource(ds).target("5").load().migrate();
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_event",String.class)).isEqualTo("DIAGNOSTIC_RESULT");
        assertThat(jdbc.queryForObject("SELECT campaign_id FROM outbox_event",Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name='outbox_event' AND column_name='result_id'",String.class)).isEqualTo("YES");
    }
}
