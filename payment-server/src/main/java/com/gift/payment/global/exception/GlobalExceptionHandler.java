package com.gift.payment.global.exception;

import java.util.UUID;

import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gift.payment.global.response.ApiErrorResponse;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(BusinessException exception) {

        ErrorCode errorCode = exception.errorCode();
        String traceId = resolveTraceId();

        log.warn("Payment Server 업무 예외가 발생했습니다. " + "traceId={}, code={}", traceId, errorCode.code());

        return response(errorCode, traceId);
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MethodValidationException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiErrorResponse> handleInvalidRequest(Exception exception) {

        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        String traceId = resolveTraceId();

        log.warn("Payment Server 요청 검증에 실패했습니다. " + "traceId={}, exception={}", traceId, exception.getClass().getSimpleName());

        return response(errorCode, traceId);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {

        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        String traceId = resolveTraceId();

        log.error("Payment Server에서 예상하지 못한 오류가 발생했습니다. " + "traceId={}", traceId, exception);

        return response(errorCode, traceId);
    }

    private ResponseEntity<ApiErrorResponse> response(ErrorCode errorCode, String traceId) {
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiErrorResponse.from(errorCode, traceId));
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);

        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }

        return UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}
