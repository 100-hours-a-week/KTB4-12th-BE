package com.gift.gift.global.exception;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.ConstraintViolation;
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

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";
    private static final Set<String> REQUIRED_CONSTRAINTS = Set.of("NotBlank", "NotEmpty", "NotNull");

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        String traceId = resolveTraceId();

        return ResponseEntity
            .status(errorCode.status())
            .body(ErrorResponse.of(errorCode, exception.getMessage(), traceId));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(
        MethodArgumentNotValidException exception
    ) {
        List<ErrorResponse.ValidationDetail> details = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::toValidationDetail)
            .distinct()
            .sorted(Comparator.comparing(ErrorResponse.ValidationDetail::field))
            .toList();

        return invalidRequest(details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(
        ConstraintViolationException exception
    ) {
        List<ErrorResponse.ValidationDetail> details = exception.getConstraintViolations()
            .stream()
            .map(this::toValidationDetail)
            .sorted(Comparator.comparing(ErrorResponse.ValidationDetail::field))
            .toList();

        return invalidRequest(details);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
        return invalidRequest(List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        String traceId = resolveTraceId();
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

        log.error("Unexpected error occurred. traceId={}", traceId, exception);

        return ResponseEntity
            .status(errorCode.status())
            .body(ErrorResponse.of(errorCode, errorCode.message(), traceId));
    }

    private ResponseEntity<ErrorResponse> invalidRequest(List<ErrorResponse.ValidationDetail> details) {
        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        String traceId = resolveTraceId();

        return ResponseEntity
            .status(errorCode.status())
            .body(ErrorResponse.of(errorCode, errorCode.message(), traceId, details));
    }

    private ErrorResponse.ValidationDetail toValidationDetail(FieldError fieldError) {
        ValidationErrorReason reason = REQUIRED_CONSTRAINTS.contains(fieldError.getCode())
            ? ValidationErrorReason.REQUIRED
            : ValidationErrorReason.INVALID_FORMAT;

        return new ErrorResponse.ValidationDetail(fieldError.getField(), reason);
    }

    private ErrorResponse.ValidationDetail toValidationDetail(ConstraintViolation<?> violation) {
        String propertyPath = violation.getPropertyPath().toString();
        int separatorIndex = propertyPath.lastIndexOf('.');
        String field = separatorIndex >= 0 ? propertyPath.substring(separatorIndex + 1) : propertyPath;
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        ValidationErrorReason reason = REQUIRED_CONSTRAINTS.contains(constraint)
            ? ValidationErrorReason.REQUIRED
            : ValidationErrorReason.INVALID_FORMAT;

        return new ErrorResponse.ValidationDetail(field, reason);
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);
        return traceId != null && !traceId.isBlank()
            ? traceId
            : UUID.randomUUID().toString().replace("-", "");
    }
}
