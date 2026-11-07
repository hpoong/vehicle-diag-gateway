package com.hopoong.diag.receiver.quality.application;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hopoong.diag.receiver.quality.web.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PayloadHasherTest {
    private final PayloadHasher hasher = new PayloadHasher();
    private final JsonMapper mapper = new JsonMapper();
    private final String body = """
            {"vin":"VIN1","source":{"system":"gateway","resultId":1},
             "eventId":"00000000-0000-0000-0000-000000000001","brand":"A",
             "occurredAt":"2026-11-05T23:10:00.000Z",
             "ecus":[{"ecuId":"BMS","dtcCodes":["P0A80","P0AFA"]},{"ecuId":"VCU","dtcCodes":["P0001"]}]}
            """;

    @Test
    void canonicalJsonHasSortedKeysAtEveryDepthAndKnownSha256() throws Exception {
        var request = mapper.readValue(body, QualityEventRequest.class);
        String expected = """
                {"brand":"A","ecus":[{"dtcCodes":["P0A80","P0AFA"],"ecuId":"BMS"},{"dtcCodes":["P0001"],"ecuId":"VCU"}],"eventId":"00000000-0000-0000-0000-000000000001","occurredAt":"2026-11-05T23:10:00.000Z","source":{"resultId":1,"system":"gateway"},"vin":"VIN1"}
                """.strip();
        assertThat(hasher.canonicalJson(request)).isEqualTo(expected);
        assertThat(hasher.hash(request)).matches("[0-9a-f]{64}")
                .isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertThat(hasher.hash(request)).isEqualTo(hasher.hash(request));
    }

    @Test
    void keyOrderAndNullDoNotChangeHashButArraysAndTimestampSpellingDo() throws Exception {
        var request = mapper.readValue(body, QualityEventRequest.class);
        var withNull = mapper.readValue(body.replace("\"ecuId\":\"VCU\"", "\"swVersion\":null,\"ecuId\":\"VCU\""), QualityEventRequest.class);
        assertThat(hasher.hash(withNull)).isEqualTo(hasher.hash(request));
        var tree = mapper.readTree(body);
        var reversed = new java.util.LinkedHashMap<String, Object>();
        var fields = new java.util.ArrayList<String>();
        tree.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.reverse(fields);
        fields.forEach(key -> reversed.put(key, tree.get(key)));
        assertThat(hasher.hash(mapper.convertValue(reversed, QualityEventRequest.class))).isEqualTo(hasher.hash(request));
        for (String changed : List.of(body.replace(".000Z", "Z"), body.replace("P0AFA", "P0B00"))) {
            assertThat(hasher.hash(mapper.readValue(changed, QualityEventRequest.class))).isNotEqualTo(hasher.hash(request));
        }
        var reordered = new QualityEventRequest(request.eventId(), request.source(), request.vin(), request.brand(),
                request.occurredAt(), request.receivedAt(), List.of(request.ecus().get(1), request.ecus().get(0)));
        assertThat(hasher.hash(reordered)).isNotEqualTo(hasher.hash(request));
    }
}
