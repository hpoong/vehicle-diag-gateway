package com.hopoong.diag.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    com.hopoong.diag.gateway.delivery.config.DeliveryProperties deliveryProperties;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestRestTemplate http;

    @Test
    void startsWithAllGatewayMigrationsAndDatabaseHealth() {
        assertThat(deliveryProperties.enabled()).isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version is not null",
                Integer.class)).isEqualTo(3);
        for (String table : new String[]{"diagnostic_result", "ecu_observation", "vehicle_ecu_latest",
                "outbox_event", "delivery_attempt", "ops_action_log"}) {
            assertThat(jdbc.queryForObject("select to_regclass(?)::text", String.class, table))
                    .isEqualTo(table);
        }
        var response = http.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
