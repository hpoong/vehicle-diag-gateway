package com.hopoong.diag.receiver.quality.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hopoong.diag.receiver.quality.web.QualityEventRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class PayloadHasher {
    private static final JsonMapper HASH_MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    String canonicalJson(QualityEventRequest request) {
        Map<String, Object> tree = HASH_MAPPER.convertValue(request, new TypeReference<>() {});
        try {
            return HASH_MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize quality event", exception);
        }
    }

    public String hash(QualityEventRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
