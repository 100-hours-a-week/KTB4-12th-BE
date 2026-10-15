package com.gift.gift.domain.order.controller;

import java.time.Clock;
import java.util.UUID;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.dto.response.PaymentCancellationResponse;
import com.gift.gift.domain.order.dto.response.PaymentCreateResponse;
import com.gift.gift.domain.order.dto.response.PaymentStatusResponse;
import com.gift.gift.domain.order.entity.CancellationSource;
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

    private final PaymentService paymentService;
    private final PaymentQueryService paymentQueryService;
    private final PaymentCancellationService paymentCancellationService;

    private final Clock clock;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentCreateResponse>> create(
            @CurrentUserId Long payerId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestHeader(value = PAYMENT_SCENARIO, required = false) String scenario,
            @Valid @RequestBody PaymentCreateRequest request
    ) {
        PaymentResult result = paymentService.pay(payerId, idempotencyKey, scenario, request);

        PaymentCreateResponse response = PaymentCreateResponse.from(result.completion(), clock.getZone());

        OrderSuccessCode successCode = result.created()
                ? OrderSuccessCode.PAYMENT_CREATED
                : OrderSuccessCode.PAYMENT_REUSED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<PaymentStatusResponse>> getStatus(
            @CurrentUserId Long payerId,
            @PathVariable UUID orderId
    ) {
        PaymentStatusResponse response = paymentQueryService.getStatus(payerId, orderId.toString());

        OrderSuccessCode successCode = OrderSuccessCode.PAYMENT_STATUS_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }

    @PostMapping("/{orderId}/cancellations")
    public ResponseEntity<
            ApiResponse<PaymentCancellationResponse>
            > cancel(
            @CurrentUserId Long requesterId,
            @PathVariable UUID orderId,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
            String idempotencyKey
    ) {
        PaymentCancellationResult result =
                paymentCancellationService.cancel(
                        requesterId,
                        orderId.toString(),
                        idempotencyKey,
                        CancellationSource.USER
                );

        PaymentCancellationResponse response =
                PaymentCancellationResponse.from(result, clock.getZone()
                );

        OrderSuccessCode successCode = result.created()
                ? OrderSuccessCode.PAYMENT_CANCELLATION_CREATED
                : OrderSuccessCode.PAYMENT_CANCELLATION_REUSED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(successCode.message(), response));
    }
}
