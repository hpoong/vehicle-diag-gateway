package com.hopoong.diag.receiver;

import com.hopoong.diag.testsupport.QualityEventFixture;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QualityEventValidationIT extends ReceiverDatabaseITSupport {
    private String sample() { return QualityEventFixture.sample(UUID.randomUUID().toString()); }
    @Test
    void returnsAllNestedFieldErrorsWithoutPersistingOrEchoingValues() throws Exception {
        var body = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(sample());
        body.put("eventId", "abc").put("vin", "123456789012345678");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.get("source")).remove("resultId");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.get("ecus").get(0)).putArray("dtcCodes").add("").add("P0A80");
        var response = post(body.toString());
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().path("type").asText()).isEqualTo("urn:diag-receiver:error:invalid-request");
        var fields = new HashSet<String>();
        response.getBody().path("errors").forEach(e -> fields.add(e.path("field").asText()));
        assertThat(fields).containsExactlyInAnyOrder("eventId", "vin", "source.resultId", "ecus[0].dtcCodes[0]");
        assertThat(response.getBody().toString()).doesNotContain("123456789012345678");
        assertEmpty();
    }
    @Test
    void rejectsUnknownMalformedNullEpochMissingOffsetAndOversizedInputs() throws Exception {
        String body = sample();
        var noEcus = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(body); noEcus.remove("ecus");
        var nullEcu = (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(body); nullEcu.putArray("ecus").addNull();
        for (String invalid : List.of("{", body.replace("\"brand\":", "\"unknown\":1,\"brand\":"),
                body.replace("2026-11-05T23:10:00.000Z", "2026-11-05T23:10:00"),
                body.replace("\"2026-11-05T23:10:00.000Z\"", "1730000000"),
                body.replace("BMS", "B".repeat(31)), noEcus.toString(), nullEcu.toString())) {
            assertThat(post(invalid).getStatusCode().value()).as(invalid).isEqualTo(400);
            assertEmpty();
        }
    }
    @Test
    void rejectsRepeatedPairsWithinAndAcrossEcusAtTheirRepeatedLocations() {
        for (String body : List.of(QualityEventFixture.body(UUID.randomUUID().toString(), 1,
                new QualityEventFixture.Ecu("BMS", null, List.of("P0A80", "P0A80"))),
                QualityEventFixture.body(UUID.randomUUID().toString(), 1,
                new QualityEventFixture.Ecu("BMS", null, List.of("P0A80")),
                new QualityEventFixture.Ecu("BMS", null, List.of("P0A80"))))) {
            var response = post(body);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().at("/errors/0/rule").asText()).isEqualTo("DUPLICATE_CASE");
            assertEmpty();
        }
    }
}
