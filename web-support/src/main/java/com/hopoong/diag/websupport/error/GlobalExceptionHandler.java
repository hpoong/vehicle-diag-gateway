package com.hopoong.diag.websupport.error;

import com.hopoong.diag.websupport.web.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private final String problemTypePrefix;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusinessException(BusinessException exception, WebRequest request) {
        ErrorCode code = exception.getErrorCode();
        ProblemDetail problem = createProblem(code);
        problem.setProperty("errors", exception.getErrors());
        return handleExceptionInternal(exception, problem, new HttpHeaders(), code.getStatus(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception, WebRequest request) {
        List<ApiFieldError> errors = exception.getConstraintViolations().stream()
                .map(violation -> new ApiFieldError(violation.getPropertyPath().toString(),
                        violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(),
                        violation.getMessage()))
                .toList();
        return handleBusinessException(new BusinessException(CommonErrorCode.INVALID_REQUEST, errors), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, WebRequest request) {
        ErrorCode code = CommonErrorCode.INTERNAL_SERVER_ERROR;
        return handleExceptionInternal(exception, createProblem(code), new HttpHeaders(), code.getStatus(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiFieldError> errors = exception.getBindingResult().getAllErrors().stream()
                .map(error -> new ApiFieldError(
                        error instanceof org.springframework.validation.FieldError fieldError
                                ? fieldError.getField() : error.getObjectName(),
                        error.getCode(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = createProblem(CommonErrorCode.INVALID_REQUEST);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        List<ApiFieldError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiFieldError(result.getMethodParameter().getParameterName(),
                                "Validation", error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = createProblem(CommonErrorCode.INVALID_REQUEST);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem;
        if (status.is5xxServerError()) {
            log.error("Request failed with HTTP {}", status.value(), exception);
        }
        if (body instanceof ProblemDetail supplied && !URI.create("about:blank").equals(supplied.getType())) {
            problem = supplied;
        } else {
            String code = status.is5xxServerError() ? "internal-server-error"
                    : status.value() == 400 ? "invalid-request"
                    : status.value() == 404 ? "not-found" : "http-" + status.value();
            problem = ProblemDetail.forStatus(status);
            problem.setType(URI.create(problemTypePrefix + code));
            problem.setTitle(status.is5xxServerError()
                    ? CommonErrorCode.INTERNAL_SERVER_ERROR.getMessage()
                    : status.value() == 400 ? CommonErrorCode.INVALID_REQUEST.getMessage() : problem.getTitle());
        }
        // 프레임워크가 보유한 405 Allow / 415 Accept 등의 헤더와 실제 HTTP 상태를 보존한다.
        problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
        return super.handleExceptionInternal(exception, problem, headers, status, request);
    }

    private ProblemDetail createProblem(ErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatus(code.getStatus());
        problem.setType(URI.create(problemTypePrefix + code.getCode()));
        problem.setTitle(code.getMessage());
        return problem;
    }
}
