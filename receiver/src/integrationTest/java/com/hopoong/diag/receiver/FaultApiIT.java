package com.hopoong.diag.receiver;

import com.hopoong.diag.receiver.fault.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.flyway.enabled=false", "debug=false"})
@AutoConfigureMockMvc
@ActiveProfiles("chaos")
class FaultApiIT {
    @Autowired MockMvc mvc;
    @Autowired FaultState state;
    @BeforeEach void reset() { state.set(new FaultConfig(FaultMode.NONE, 15_000)); }
    @Test
    void changesModeAndUsesDefaultHold() throws Exception {
        mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"DROP_AFTER_COMMIT\",\"holdMs\":2000}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("DROP_AFTER_COMMIT"))
                .andExpect(jsonPath("$.data.holdMs").value(2000));
        assertThat(state.current()).isEqualTo(new FaultConfig(FaultMode.DROP_AFTER_COMMIT, 2000));
        mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"DOWN\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.holdMs").value(15000));
        assertThat(state.current()).isEqualTo(new FaultConfig(FaultMode.DOWN, 15000));
    }
    @Test
    void invalidConfigurationDoesNotMutateState() throws Exception {
        for (String body : java.util.List.of("{}", "{\"mode\":\"SLOW\"}", "{\"mode\":\"down\"}",
                "{\"mode\":\"NONE\",\"holdMs\":-1}", "{\"mode\":\"NONE\",\"holdMs\":60001}")) {
            mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("urn:diag-receiver:error:invalid-request"));
            assertThat(state.current()).isEqualTo(new FaultConfig(FaultMode.NONE, 15000));
        }
    }
}
