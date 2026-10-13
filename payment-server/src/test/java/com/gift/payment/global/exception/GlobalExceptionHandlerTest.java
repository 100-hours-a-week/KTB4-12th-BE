package com.gift.payment.global.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

import com.gift.payment.global.response.ApiErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private static final String TRACE_ID = "payment-test-trace-id";

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        MDC.put("traceId", TRACE_ID);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("업무 예외는 정의된 상태와 오류 코드로 반환한다")
    void handleBusinessException_returnsDefinedError() {
        BusinessException exception = new BusinessException(ErrorCode.ORDER_ID_CONFLICT);

        ResponseEntity<ApiErrorResponse> response = handler.handleBusinessException(exception);

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.ORDER_ID_CONFLICT.status());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(ErrorCode.ORDER_ID_CONFLICT.message());
        assertThat(response.getBody().error().code()).isEqualTo("ORDER_ID_CONFLICT");
        assertThat(response.getBody().error().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("요청 검증 오류는 400 INVALID_REQUEST로 반환한다")
    void handleInvalidRequest_returnsBadRequest() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleInvalidRequest(new IllegalArgumentException("잘못된 요청"));

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.INVALID_REQUEST.status());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("INVALID_REQUEST");
        assertThat(response.getBody().error().traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("예상하지 못한 오류는 500 공통 오류로 반환한다")
    void handleUnexpectedException_returnsInternalError() {
        ResponseEntity<ApiErrorResponse> response =
                handler.handleUnexpectedException(new IllegalStateException("DB 오류"));

        assertThat(response.getStatusCode()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.status());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().error().traceId()).isEqualTo(TRACE_ID);
    }
}
