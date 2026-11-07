package com.hopoong.diag.receiver.quality.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QualityEventRequestTest {
    @Test
    void acceptsOptionalSoftwareVersionAndOffsetTimeButRejectsMissingOffsetAndNullElements() throws Exception {
        var mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        var body = """
                {"eventId":"00000000-0000-0000-0000-000000000001","source":{"resultId":1},"vin":"VIN1",
                 "occurredAt":"2026-11-05T23:10:00.000Z","ecus":[{"ecuId":"BMS","dtcCodes":["P0A80"]}]}
                """;
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(mapper.readValue(body, QualityEventRequest.class))).isEmpty();
            assertThat(validator.validate(mapper.readValue(body.replace(".000Z", "+09:00"), QualityEventRequest.class))).isEmpty();
            for (String invalid : List.of(body.replace(".000Z", ""), body.replace("[\"P0A80\"]", "[null]"),
                    body.replace("[{\"ecuId\":\"BMS\",\"dtcCodes\":[\"P0A80\"]}]", "[null]"))) {
                assertThat(validator.validate(mapper.readValue(invalid, QualityEventRequest.class))).isNotEmpty();
            }
        }
    }
}
