package com.gift.gift.domain.order.controller;

import java.time.Clock;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.dto.response.PaymentCreateResponse;
import com.gift.gift.domain.order.response.OrderSuccessCode;
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
    private final Clock clock;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentCreateResponse>> create(
            @CurrentUserId Long payerId,
            @RequestHeader(
                    value = IDEMPOTENCY_KEY,
                    required = false
            )
            String idempotencyKey,
            @RequestHeader(
                    value = PAYMENT_SCENARIO,
                    required = false
            )
            String scenario,
            @Valid @RequestBody PaymentCreateRequest request
    ) {
        PaymentResult result = paymentService.pay(
                payerId,
                idempotencyKey,
                scenario,
                request
        );

        PaymentCreateResponse response =
                PaymentCreateResponse.from(result.completion(), clock.getZone());

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
}
