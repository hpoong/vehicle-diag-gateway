package com.hopoong.diag.websupport.response;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 성공 응답 전용. HTTP 상태(200, 201, 202 등)는 컨트롤러에서 지정한다. */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiResponse<T> {

    private final T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(null);
    }
}
