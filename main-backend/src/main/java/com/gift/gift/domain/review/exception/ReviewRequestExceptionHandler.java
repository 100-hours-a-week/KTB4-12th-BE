package com.gift.gift.domain.review.exception;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import com.gift.gift.domain.review.controller.ReviewController;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.ValidationDetail;
import com.gift.gift.global.exception.ValidationErrorReason;
import com.gift.gift.global.response.ApiResponse;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReviewController.class)
public class ReviewRequestExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        return invalidRequest(toValidationDetails(
                exception.getBindingResult().getFieldErrors()
        ));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception
    ) {
        if (exception.isForReturnValue()) {
            throw exception;
        }

        List<FieldError> fieldErrors = exception
                .getParameterValidationResults()
                .stream()
                .filter(ParameterErrors.class::isInstance)
                .map(ParameterErrors.class::cast)
                .flatMap(errors -> errors.getFieldErrors().stream())
                .toList();

        return invalidRequest(toValidationDetails(fieldErrors));
    }

    private ResponseEntity<ApiResponse<Void>> invalidRequest(
            List<ValidationDetail> details
    ) {

        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        String traceId = resolveTraceId();

        log.warn(
                "리뷰 요청이 거부되었습니다. traceId={}, code={}, detailCount={}",
                traceId,
                errorCode.code(),
                details.size()
        );

        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.error(
                        errorCode,
                        details.isEmpty()
                                ? errorCode.message()
                                : ReviewErrorCode.REVIEW_INVALID_REQUEST.message(),
                        traceId,
                        details
                ));
    }

    private List<ValidationDetail> toValidationDetails(
            List<FieldError> fieldErrors
    ) {
        return fieldErrors.stream()
                .filter(this::shouldIncludeValidationDetail)
                .map(this::toValidationDetail)
                .distinct()
                .sorted(Comparator.comparing(ValidationDetail::field)
                        .thenComparing(detail -> detail.reason().name()))
                .toList();
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
