package com.hopoong.diag.gateway.ingest.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import com.hopoong.diag.gateway.common.UtcTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import com.hopoong.diag.gateway.ingest.domain.DiagnosticResultCommand;
import com.hopoong.diag.gateway.ingest.domain.EcuCommand;
import com.hopoong.diag.gateway.ingest.web.DiagnosticResultRequest;
import com.hopoong.diag.gateway.ingest.web.EcuRequest;

@Component
public class DiagnosticResultNormalizer {
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper();
    private final ObjectMapper objectMapper;

    public DiagnosticResultNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizedRequest normalize(DiagnosticResultRequest request) {
        List<EcuCommand> ecus = IntStream.range(0, request.ecus().size())
                .mapToObj(index -> {
                    EcuRequest ecu = request.ecus().get(index);
                    return new EcuCommand(ecu.ecuId(), ecu.swVersion(),
                            ecu.dtcCodes().stream().sorted().toList(), index);
                })
                .sorted(Comparator.comparing(EcuCommand::ecuId).thenComparingInt(EcuCommand::requestIndex))
                .toList();
        var command = new DiagnosticResultCommand(request.deviceId(), request.eventId(), request.brand(),
                request.vin(), request.sessionId(), OffsetDateTime.parse(request.occurredAt())
                        .withOffsetSameInstant(ZoneOffset.UTC), ecus);
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("brand", command.brand());
        canonical.put("deviceId", command.deviceId());
        canonical.put("ecus", ecus.stream().map(DiagnosticResultNormalizer::canonicalEcu).toList());
        canonical.put("eventId", command.eventId());
        canonical.put("occurredAt", UtcTime.MILLIS.format(command.occurredAt().toInstant()));
        canonical.put("sessionId", command.sessionId());
        canonical.put("vin", command.vin());
        try {
            byte[] bytes = HASH_MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new NormalizedRequest(command, hash, objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to normalize diagnostic result", exception);
        }
    }

    private static Map<String, Object> canonicalEcu(EcuCommand ecu) {
        Map<String, Object> result = new TreeMap<>();
        result.put("dtcCodes", ecu.dtcCodes());
        result.put("ecuId", ecu.ecuId());
        if (ecu.swVersion() != null) {
            result.put("swVersion", ecu.swVersion());
        }
        return result;
    }
}
