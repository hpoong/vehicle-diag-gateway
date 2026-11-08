package com.hopoong.diag.gateway.common.error;

import com.hopoong.diag.websupport.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** gateway의 업무 오류만 정의한다. receiver는 자신의 에러 코드를 별도로 정의한다. */
@Getter
@RequiredArgsConstructor
public enum GatewayErrorCode implements ErrorCode {
    CURRENT_PART_CONFLICT(HttpStatus.CONFLICT, "current-part-conflict", "같은 장착 자리에 현재 장착된 부품이 이미 있습니다."),
    PART_BATCH_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "part-batch-violation", "생산 이력 배치 규칙을 위반했습니다."),
    INVALID_TRACE_WINDOW(HttpStatus.BAD_REQUEST, "invalid-trace-window", "조회 기간이 올바르지 않습니다."),
    CAMPAIGN_REQUEST_CONFLICT(HttpStatus.CONFLICT, "campaign-request-conflict", "같은 요청 ID에 다른 캠페인 내용이 전달되었습니다."),
    CAMPAIGN_TARGET_CHANGED(HttpStatus.CONFLICT, "campaign-target-changed", "대상 차량이 확정 중에 바뀌었습니다. 다시 요청하세요."),
    NO_CAMPAIGN_TARGET(HttpStatus.UNPROCESSABLE_ENTITY, "no-campaign-target", "조치 대상 차량이 없습니다."),
    CAMPAIGN_TOO_LARGE(HttpStatus.UNPROCESSABLE_ENTITY, "campaign-too-large", "조치 대상 차량 수가 상한을 넘습니다."),
    EVENT_CONFLICT(HttpStatus.CONFLICT, "event-conflict", "동일한 이벤트 ID에 다른 내용이 전달되었습니다."),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "invalid-state-transition", "허용되지 않는 상태 변경입니다."),
    COMMON_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "common-rule-violation", "공통 검증 규칙을 위반했습니다."),
    POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "policy-violation", "브랜드 정책을 위반했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
