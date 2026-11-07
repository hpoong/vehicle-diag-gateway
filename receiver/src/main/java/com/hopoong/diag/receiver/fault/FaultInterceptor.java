package com.hopoong.diag.receiver.fault;

import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@RequiredArgsConstructor
public class FaultInterceptor implements HandlerInterceptor {
    private final FaultState state;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (state.current().mode() == FaultMode.DOWN) {
            log.info("FAULT_APPLIED mode=DOWN");
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE);
        }
        return true;
    }
}
