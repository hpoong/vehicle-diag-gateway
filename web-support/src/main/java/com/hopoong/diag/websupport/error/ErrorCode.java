package com.hopoong.diag.websupport.error;

import org.springframework.http.HttpStatus;

/** 각 서비스는 자신의 enum에서 이 인터페이스를 구현해 에러를 확장한다. */
public interface ErrorCode {
    HttpStatus getStatus();
    String getCode();
    String getMessage();
}
