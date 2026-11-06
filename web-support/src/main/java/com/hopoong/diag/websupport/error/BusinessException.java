package com.hopoong.diag.websupport.error;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private static final Set<String> RESERVED_PROPERTIES = Set.of(
            "type", "title", "status", "detail", "instance", "errors", "requestId");
    private final ErrorCode errorCode;
    private final List<ApiFieldError> errors;
    private final Map<String, Object> properties;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public BusinessException(ErrorCode errorCode, List<ApiFieldError> errors) {
        this(errorCode, errors, Map.of());
    }

    public BusinessException(ErrorCode errorCode, List<ApiFieldError> errors, Map<String, Object> properties) {
        super(Objects.requireNonNull(errorCode).getMessage());
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
        this.properties = Map.copyOf(properties);
        if (this.properties.keySet().stream().anyMatch(RESERVED_PROPERTIES::contains)) {
            throw new IllegalArgumentException("Reserved problem detail property");
        }
    }
}
