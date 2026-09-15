package com.gift.gift.global.exception;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gift.gift.global.response.ApiResponse;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";
    private static final Set<String> REQUIRED_CONSTRAINTS = Set.of("NotBlank", "NotEmpty", "NotNull");

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        String traceId = resolveTraceId();

        log.warn("Business exception occurred. traceId={}, code={}", traceId, errorCode.code());

        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.error(errorCode, exception.getMessage(), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        List<ApiResponse.ValidationDetail> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toValidationDetail)
                .distinct()
                .sorted(Comparator.comparing(ApiResponse.ValidationDetail::field))
                .toList();

        return invalidRequest(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        return invalidRequest(List.of());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception) {
        return invalidRequest(List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        String traceId = resolveTraceId();
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

        log.error("Unexpected error occurred. traceId={}", traceId, exception);

        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.error(errorCode, errorCode.message(), traceId));
    }

    private ResponseEntity<ApiResponse<Void>> invalidRequest(List<ApiResponse.ValidationDetail> details) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        String traceId = resolveTraceId();

        log.warn("Invalid request. traceId={}, detailCount={}", traceId, details.size());

        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.error(errorCode, errorCode.message(), traceId, details));
    }

    private ApiResponse.ValidationDetail toValidationDetail(FieldError fieldError) {
        ValidationErrorReason reason = REQUIRED_CONSTRAINTS.contains(fieldError.getCode())
                ? ValidationErrorReason.REQUIRED
                : ValidationErrorReason.INVALID_FORMAT;

        return new ApiResponse.ValidationDetail(fieldError.getField(), reason);
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId != null && !traceId.isBlank()
                ? traceId
                : UUID.randomUUID().toString().replace("-", "");
    }
}
