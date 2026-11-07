package com.hopoong.diag.gateway.ingest.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import com.hopoong.diag.gateway.ingest.domain.EcuCommand;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultRequest;

class DiagnosticResultNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagnosticResultNormalizer normalizer = new DiagnosticResultNormalizer(mapper);

    private static final String REQUEST = """
            {"deviceId":"device","eventId":"event","brand":"BRAND","vin":"1HGCM82633A004352",
             "sessionId":"session","occurredAt":"2026-11-06T08:10:00+09:00",
             "ecus":[{"ecuId":"VCU","swVersion":null,"dtcCodes":[]},
                     {"ecuId":"BMS","swVersion":"1","dtcCodes":["P0AFA","P0A80"]}]}
            """;
    private static final String EQUIVALENT = """
            {"vin":"1HGCM82633A004352","ecus":[
               {"dtcCodes":["P0A80","P0AFA"],"swVersion":"1","ecuId":"BMS"},
               {"dtcCodes":[],"ecuId":"VCU"}],
             "occurredAt":"2026-11-05T23:10:00Z","sessionId":"session",
             "eventId":"event","deviceId":"device","brand":"BRAND"}
            """;

    private NormalizedRequest normalize(String json) throws Exception {
        return normalizer.normalize(mapper.readValue(json, DiagnosticResultRequest.class));
    }

    @Test
    void normalizesTimeAndOrderWhilePreservingOriginalIndexesAndRawTimestamp() throws Exception {
        var result = normalize(REQUEST);
        assertThat(result.command().occurredAt()).isEqualTo(OffsetDateTime.parse("2026-11-05T23:10:00Z"));
        assertThat(result.command().ecus()).extracting(EcuCommand::ecuId).containsExactly("BMS", "VCU");
        assertThat(result.command().ecus()).extracting(EcuCommand::requestIndex).containsExactly(1, 0);
        assertThat(result.command().ecus().get(0).dtcCodes()).containsExactly("P0A80", "P0AFA");
        assertThat(result.rawPayload()).contains("\"occurredAt\":\"2026-11-06T08:10:00+09:00\"");
    }

    @Test
    void hashesEquivalentRepresentationsEquallyAndDifferentContentDifferently() throws Exception {
        var original = normalize(REQUEST);
        var equivalent = normalize(EQUIVALENT);
        var changed = normalize(EQUIVALENT.replace("\"P0AFA\"", "\"P0AFA\",\"P0123\""));
        assertThat(original.payloadHash()).matches("[0-9a-f]{64}");
        assertThat(equivalent.payloadHash()).isEqualTo(original.payloadHash());
        assertThat(changed.payloadHash()).isNotEqualTo(original.payloadHash());
    }

    @Test
    void keepsRequestOrderForDuplicateEcuIds() throws Exception {
        var result = normalize(REQUEST.replace("VCU", "BMS"));
        assertThat(result.command().ecus()).extracting(EcuCommand::requestIndex).containsExactly(0, 1);
    }
}
