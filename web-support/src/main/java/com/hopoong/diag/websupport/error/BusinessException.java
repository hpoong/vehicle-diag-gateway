package com.hopoong.diag.websupport.error;

import java.util.List;
import java.util.Objects;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final List<ApiFieldError> errors;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public BusinessException(ErrorCode errorCode, List<ApiFieldError> errors) {
        super(Objects.requireNonNull(errorCode).getMessage());
        this.errorCode = errorCode;
        this.errors = List.copyOf(errors);
    }
}
