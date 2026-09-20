package com.gift.gift.global.exception;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.gift.gift.domain.user.exception.LoginRateLimitExceededException;
import com.gift.gift.global.response.ApiResponse;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        List<ValidationDetail> details = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .filter(this::shouldIncludeValidationDetail)
                .map(this::toValidationDetail)
                .distinct()
                .sorted(Comparator.comparing(ValidationDetail::field)
                        .thenComparing(detail -> detail.reason().name()))
                .toList();

        return requestError(ErrorCode.INVALID_REQUEST, details);
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleRequestValidationException(
            RequestValidationException exception
    ) {
        return requestError(exception.getErrorCode(), List.of());
    }

    @ExceptionHandler(LoginRateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>>
    handleLoginRateLimitExceededException(
            LoginRateLimitExceededException exception
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        String traceId = resolveTraceId();

        log.warn(
                "Login request rate limited. traceId={}, retryAfterSeconds={}",
                traceId,
                exception.getRetryAfterSeconds()
        );

        return ResponseEntity
                .status(errorCode.status())
                .header(
                        HttpHeaders.RETRY_AFTER,
                        Long.toString(
                                exception.getRetryAfterSeconds()
                        )
                )
                .body(
                        ApiResponse.error(
                                errorCode,
                                errorCode.message(),
                                traceId
                        )
                );
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception
    ) {
        ErrorCode errorCode = exception.getErrorCode();
        String traceId = resolveTraceId();

        log.warn(
                "Business exception occurred. traceId={}, code={}",
                traceId,
                errorCode.code()
        );

        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.error(errorCode, exception.getMessage(), traceId));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        return requestError(ErrorCode.INVALID_REQUEST, List.of());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception
    ) {
        if (exception.isForReturnValue()) {
            return handleUnexpectedException(exception);
        }

        return requestError(ErrorCode.INVALID_REQUEST, List.of());
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception) {
        return requestError(ErrorCode.INVALID_REQUEST, List.of());
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

    private ResponseEntity<ApiResponse<Void>> requestError(
            ErrorCode errorCode,
            List<ValidationDetail> details
    ) {
        String traceId = resolveTraceId();

        log.warn(
                "Request rejected. traceId={}, code={}, detailCount={}",
                traceId,
                errorCode.code(),
                details.size()
        );

        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.error(
                        errorCode,
                        errorCode.message(),
                        traceId,
                        details
                ));
    }

    private boolean shouldIncludeValidationDetail(FieldError fieldError) {
        if (fieldError.isBindingFailure()) {
            return false;
        }

        String message = fieldError.getDefaultMessage();

        for (ValidationErrorReason reason : ValidationErrorReason.values()) {
            if (reason.name().equals(message)) {
                return true;
            }
        }

        return false;
    }

    private ValidationDetail toValidationDetail(FieldError fieldError) {
        return new ValidationDetail(
                fieldError.getField(),
                ValidationErrorReason.valueOf(fieldError.getDefaultMessage())
        );
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);

        return traceId != null && !traceId.isBlank()
                ? traceId
                : UUID.randomUUID().toString().replace("-", "");
    }
}
