package com.gift.gift.domain.friend.exception;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gift.gift.domain.friend.controller.FriendController;
import com.gift.gift.global.exception.ValidationDetail;
import com.gift.gift.global.exception.ValidationErrorReason;
import com.gift.gift.global.response.ApiResponse;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FriendController.class)
public class FriendRequestExceptionHandler {

    private static final String TRACE_ID_KEY = "traceId";
    private static final String FRIEND_USER_ID_FIELD = "friendUserId";

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

        return invalidCreateRequest(details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadableException(
            HttpMessageNotReadableException exception
    ) {
        return invalidCreateRequest(List.of(
                new ValidationDetail(
                        FRIEND_USER_ID_FIELD,
                        ValidationErrorReason.INVALID_FORMAT
                )
        ));
    }

    private ResponseEntity<ApiResponse<Void>> invalidCreateRequest(
            List<ValidationDetail> details
    ) {
        FriendErrorCode friendErrorCode =
                FriendErrorCode.FRIEND_CREATE_INVALID_REQUEST;
        String traceId = resolveTraceId();

        log.warn(
                "친구 추가 요청이 거부되었습니다. traceId={}, detailCount={}",
                traceId,
                details.size()
        );

        return ResponseEntity
                .status(friendErrorCode.errorCode().status())
                .body(ApiResponse.error(
                        friendErrorCode.errorCode(),
                        friendErrorCode.message(),
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
