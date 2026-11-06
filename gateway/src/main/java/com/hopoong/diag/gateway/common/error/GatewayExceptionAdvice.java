package com.hopoong.diag.gateway.common.error;

import com.hopoong.diag.websupport.error.BusinessException;
import com.hopoong.diag.websupport.error.CommonErrorCode;
import com.hopoong.diag.websupport.error.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GatewayExceptionAdvice {
    private final GlobalExceptionHandler globalExceptionHandler;

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<Object> handleDatabaseUnavailable(Exception exception, WebRequest request) {
        log.warn("Database unavailable during request", exception);
        return globalExceptionHandler.handleBusinessException(
                new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE), request);
    }
}
