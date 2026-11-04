package com.gift.gift.domain.order.controller;

import java.time.Clock;
import java.util.UUID;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.dto.response.PaymentCancellationProcessingResponse;
import com.gift.gift.domain.order.dto.response.PaymentCancellationResponse;
import com.gift.gift.domain.order.dto.response.PaymentCreateResponse;
import com.gift.gift.domain.order.dto.response.PaymentProcessingResponse;
import com.gift.gift.domain.order.dto.response.PaymentStatusResponse;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.response.OrderSuccessCode;
import com.gift.gift.domain.order.service.PaymentCancellationResult;
import com.gift.gift.domain.order.service.PaymentCancellationService;
import com.gift.gift.domain.order.service.PaymentQueryService;
import com.gift.gift.domain.order.service.PaymentResult;
import com.gift.gift.domain.order.service.PaymentService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String PAYMENT_SCENARIO = "X-Payment-Scenario";
    private static final int RETRY_AFTER_SECONDS = 5;

    private final PaymentService paymentService;
    private final PaymentQueryService paymentQueryService;
    private final PaymentCancellationService paymentCancellationService;
    private final Clock clock;

    @PostMapping
    public ResponseEntity<ApiResponse<?>> create(
            @CurrentUserId Long payerId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
            String idempotencyKey,
            @RequestHeader(value = PAYMENT_SCENARIO, required = false)
            String scenario,
            @Valid @RequestBody PaymentCreateRequest request
    ) {
        PaymentResult result =
                paymentService.pay(payerId, idempotencyKey, scenario, request);

        if (result.isProcessing()) {
            OrderSuccessCode successCode =
                    result.processing().approvalResult()
                            == PaymentApprovalResult.APPROVED
                            ? OrderSuccessCode.PAYMENT_LOCAL_PROCESSING
                            : OrderSuccessCode.PAYMENT_PROCESSING;

            PaymentProcessingResponse response = PaymentProcessingResponse.from(
                    result.processing(),
                    RETRY_AFTER_SECONDS
            );

            return ResponseEntity
                    .status(successCode.status())
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(RETRY_AFTER_SECONDS)
                    )
                    .body(ApiResponse.success(successCode.message(), response));
        }

        PaymentCreateResponse response =
                PaymentCreateResponse.from(result.completion(), clock.getZone());

        OrderSuccessCode successCode = result.created()
                ? OrderSuccessCode.PAYMENT_CREATED
                : OrderSuccessCode.PAYMENT_REUSED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(successCode.message(), response));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getStatus(
            @CurrentUserId Long payerId,
            @PathVariable UUID orderId
    ) {
        PaymentStatusResponse response =
                paymentQueryService.getStatus(payerId, orderId.toString());

        return ResponseEntity
                .status(OrderSuccessCode.PAYMENT_STATUS_RETRIEVED.status())
                .body(ApiResponse.success(
                        OrderSuccessCode.PAYMENT_STATUS_RETRIEVED.message(),
                        response
                ));
    }

    @PostMapping("/{orderId}/cancellations")
    public ResponseEntity<ApiResponse<?>> cancel(
            @CurrentUserId Long requesterId,
            @PathVariable UUID orderId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
            String idempotencyKey
    ) {
        try {
            PaymentCancellationResult result =
                    paymentCancellationService.cancel(
                            requesterId,
                            orderId.toString(),
                            idempotencyKey,
                            CancellationSource.USER
                    );

            PaymentCancellationResponse response =
                    PaymentCancellationResponse.from(result, clock.getZone());

            OrderSuccessCode successCode = result.created()
                    ? OrderSuccessCode.PAYMENT_CANCELLATION_CREATED
                    : OrderSuccessCode.PAYMENT_CANCELLATION_REUSED;

            return ResponseEntity
                    .status(successCode.status())
                    .body(ApiResponse.success(successCode.message(), response));
        } catch (OrderException exception) {
            if (exception.getErrorCode()
                    != OrderErrorCode.CANCELLATION_RESULT_UNCONFIRMED.errorCode()) {
                throw exception;
            }

            PaymentStatusResponse status = paymentQueryService.getStatus(
                    requesterId,
                    orderId.toString()
            );
            PaymentCancellationProcessingResponse response =
                    PaymentCancellationProcessingResponse.from(
                            status,
                            RETRY_AFTER_SECONDS
                    );
            OrderSuccessCode successCode =
                    OrderSuccessCode.PAYMENT_CANCELLATION_PROCESSING;

            return ResponseEntity
                    .status(successCode.status())
                    .header(
                            HttpHeaders.RETRY_AFTER,
                            String.valueOf(RETRY_AFTER_SECONDS)
                    )
                    .body(ApiResponse.success(successCode.message(), response));
        }
    }
}
