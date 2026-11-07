package com.hopoong.diag.receiver.common.error;

import com.hopoong.diag.websupport.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReceiverErrorCode implements ErrorCode {
    EVENT_CONFLICT(HttpStatus.CONFLICT, "event-conflict", "동일한 이벤트 ID에 다른 내용이 전달되었습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
