package com.gift.payment.payment.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.payment.global.response.ApiSuccessResponse;
import com.gift.payment.payment.dto.request.PaymentConfirmRequest;
import com.gift.payment.payment.dto.response.PaymentCancelResponse;
import com.gift.payment.payment.dto.response.PaymentResponse;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.service.PaymentApprovalService;
import com.gift.payment.payment.service.PaymentCancelService;
import com.gift.payment.payment.service.PaymentQueryService;
import com.gift.payment.payment.support.PaymentScenario;
import com.gift.payment.payment.support.PaymentScenarioResolver;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    public static final String SCENARIO_HEADER = "X-Payment-Scenario";

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-"
                    + "[0-9a-fA-F]{4}-"
                    + "[1-5][0-9a-fA-F]{3}-"
                    + "[89abAB][0-9a-fA-F]{3}-"
                    + "[0-9a-fA-F]{12}$";

    private static final String IDEMPOTENCY_KEY_PATTERN = "^[A-Za-z0-9:_-]{1,100}$";

    private final PaymentApprovalService approvalService;
    private final PaymentQueryService queryService;
    private final PaymentCancelService cancelService;
    private final PaymentScenarioResolver scenarioResolver;

    @PostMapping("/confirm")
    public ResponseEntity<ApiSuccessResponse<PaymentResponse>> confirm(
            @Valid @RequestBody PaymentConfirmRequest request,
            @RequestHeader(value = SCENARIO_HEADER, required = false)
            String scenarioHeader
    ) {
        PaymentScenario scenario = scenarioResolver.resolve(scenarioHeader);

        Payment payment = approvalService.confirm(
                request.orderId(),
                request.amount(),
                scenario
        );

        return ResponseEntity.ok(
                ApiSuccessResponse.of("결제 요청을 처리했습니다.", PaymentResponse.from(payment))
        );
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<ApiSuccessResponse<PaymentResponse>>
    getByOrderId(
            @PathVariable
            @Pattern(regexp = UUID_PATTERN)
            String orderId
    ) {
        Payment payment = queryService.getByOrderId(orderId);

        return ResponseEntity.ok(
                ApiSuccessResponse.of("결제 상태를 조회했습니다.", PaymentResponse.from(payment))
        );
    }

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<ApiSuccessResponse<PaymentCancelResponse>>
    cancel(
            @PathVariable
            @Pattern(regexp = UUID_PATTERN)
            String paymentKey,

            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
            @NotBlank
            @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN)
            String idempotencyKey
    ) {
        PaymentCancel cancellation = cancelService.cancel(paymentKey, idempotencyKey);

        return ResponseEntity.ok(
                ApiSuccessResponse.of("결제를 취소했습니다.", PaymentCancelResponse.from(cancellation))
        );
    }

    @GetMapping("/{paymentKey}/cancellations")
    public ResponseEntity<
            ApiSuccessResponse<PaymentCancelResponse>> getCancellation(
            @PathVariable
            @Pattern(regexp = UUID_PATTERN) String paymentKey,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)

            @NotBlank @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN) String idempotencyKey
    ) {
        PaymentCancel cancellation = queryService.getCancellation(paymentKey, idempotencyKey);

        return ResponseEntity.ok(ApiSuccessResponse.of("결제 취소 상태를 조회했습니다.", PaymentCancelResponse.from(cancellation))
        );
    }
}
