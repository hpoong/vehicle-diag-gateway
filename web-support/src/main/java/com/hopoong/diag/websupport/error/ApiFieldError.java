package com.hopoong.diag.websupport.error;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 거절된 값은 민감정보 노출을 막기 위해 응답에 넣지 않는다. */
@Getter
@AllArgsConstructor
public final class ApiFieldError {
    private final String field;
    private final String rule;
    private final String message;
}
