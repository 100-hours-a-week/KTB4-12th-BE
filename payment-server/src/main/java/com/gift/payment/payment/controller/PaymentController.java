package com.gift.payment.payment.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.payment.global.response.ApiSuccessResponse;
import com.gift.payment.payment.dto.request.PaymentConfirmRequest;
import com.gift.payment.payment.dto.response.PaymentResponse;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.service.PaymentApprovalService;
import com.gift.payment.payment.support.PaymentScenario;
import com.gift.payment.payment.support.PaymentScenarioResolver;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    public static final String SCENARIO_HEADER = "X-Payment-Scenario";

    private final PaymentApprovalService approvalService;
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
}
