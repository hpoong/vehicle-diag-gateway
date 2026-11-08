package com.hopoong.diag.gateway.campaign;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hopoong.diag.gateway.ingest.application.IngestService;
import com.hopoong.diag.testsupport.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
@Testcontainers
@SpringBootTest(properties="gateway.delivery.enabled=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
abstract class CampaignITSupport {
    @Container @ServiceConnection static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IngestService ingest;
    @BeforeEach void reset() {
        new OutboxFixture(ingest,jdbc).reset();
        CampaignFixture.loadLot(jdbc,"TCM","L-A",4,1);CampaignFixture.loadLot(jdbc,"TCM","L-B",2,0);
    }
    org.springframework.test.web.servlet.ResultActions create(Object request) throws Exception {
        return mvc.perform(post("/api/v1/campaigns").contentType("application/json").content(json.writeValueAsString(request)));
    }
}
