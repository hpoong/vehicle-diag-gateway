package com.hopoong.diag.gateway.scenario;

import java.sql.DriverManager;
import org.testcontainers.containers.PostgreSQLContainer;

/** JVM 수명 동안 gateway/receiver DB를 제공한다. 종료는 Testcontainers가 담당한다. */
public final class ScenarioPostgres {
    private static final PostgreSQLContainer<?> POSTGRES = start();
    private ScenarioPostgres() {}
    private static PostgreSQLContainer<?> start() {
        var postgres = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("gateway");
        postgres.start();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE receiver");
        } catch (java.sql.SQLException exception) {
            postgres.stop();
            throw new IllegalStateException("receiver DB 생성 실패", exception);
        }
        return postgres;
    }
    public static String gatewayJdbcUrl() { return POSTGRES.getJdbcUrl(); }
    public static String receiverJdbcUrl() { return gatewayJdbcUrl().replace("/gateway", "/receiver"); }
    public static String username() { return POSTGRES.getUsername(); }
    public static String password() { return POSTGRES.getPassword(); }
}
