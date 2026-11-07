package com.hopoong.diag.gateway.ingest.web;
import com.hopoong.diag.websupport.error.GlobalExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice(assignableTypes = DiagnosticResultController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestRejectionLogAdvice {
    private static final Logger log = LoggerFactory.getLogger(IngestRejectionLogAdvice.class);
    private final GlobalExceptionHandler handler;
    public IngestRejectionLogAdvice(GlobalExceptionHandler handler) { this.handler = handler; }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Object> invalidRequest(Exception exception, WebRequest request) throws Exception {
        log.info("INGEST_REJECTED(invalid-request)");
        return handler.handleException(exception, request);
    }
}
