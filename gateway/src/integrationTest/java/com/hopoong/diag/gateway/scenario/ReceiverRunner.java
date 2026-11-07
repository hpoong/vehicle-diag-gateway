package com.hopoong.diag.gateway.scenario;

import com.hopoong.diag.receiver.ReceiverApplication;
import com.hopoong.diag.receiver.fault.*;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

public final class ReceiverRunner implements AutoCloseable {
    private final ServletWebServerApplicationContext context;
    private ReceiverRunner(ServletWebServerApplicationContext context) { this.context = context; }
    public static ReceiverRunner start(boolean naiveInsert) {
        var context = new SpringApplicationBuilder(ReceiverApplication.class).profiles("chaos").run(
                "--spring.config.location=classpath:/receiver-it/application.yml",
                "--spring.flyway.locations=classpath:receiver-it/db/migration",
                "--mybatis.mapper-locations=classpath:receiver-it/mapper/**/*.xml",
                "--spring.datasource.url=" + ScenarioPostgres.receiverJdbcUrl(),
                "--spring.datasource.username=" + ScenarioPostgres.username(),
                "--spring.datasource.password=" + ScenarioPostgres.password(),
                "--server.port=0", "--receiver.compare.naive-insert=" + naiveInsert);
        return new ReceiverRunner((ServletWebServerApplicationContext) context);
    }
    public String url() { return "http://localhost:" + context.getWebServer().getPort(); }
    public FaultState faults() { return context.getBean(FaultState.class); }
    public JdbcTemplate jdbc() { return new JdbcTemplate(context.getBean(DataSource.class)); }
    public void reset() {
        faults().set(new FaultConfig(FaultMode.NONE, 1500));
        jdbc().execute("TRUNCATE quality_case, quality_case_naive, received_event RESTART IDENTITY CASCADE");
    }
    @Override public void close() { context.close(); }
}
