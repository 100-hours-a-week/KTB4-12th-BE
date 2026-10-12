package com.gift.payment.payment.controller;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.service.PaymentApprovalService;
import com.gift.payment.payment.support.PaymentScenario;
import com.gift.payment.payment.support.PaymentScenarioResolver;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    private static final long AMOUNT = 64_000L;
    private static final String REQUEST_HASH = "a".repeat(64);

    private PaymentApprovalService approvalService;
    private PaymentScenarioResolver scenarioResolver;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        approvalService = mock(PaymentApprovalService.class);
        scenarioResolver = mock(PaymentScenarioResolver.class);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new PaymentController(
                                approvalService,
                                scenarioResolver
                        )
                )
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    @DisplayName("결제 승인 요청은 승인 결과를 반환한다")
    void confirm_returnsPayment() throws Exception {
        String orderId = UUID.randomUUID().toString();
        Payment payment = completedPayment(orderId);

        when(scenarioResolver.resolve("APPROVE"))
                .thenReturn(
                        PaymentScenario.APPROVE
                );
        when(approvalService.confirm(
                orderId,
                AMOUNT,
                PaymentScenario.APPROVE
        )).thenReturn(payment);

        mockMvc.perform(post("/payments/confirm")
                        .header(
                                PaymentController.SCENARIO_HEADER,
                                "APPROVE"
                        )
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "%s",
                                  "amount": 64000
                                }
                                """.formatted(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("결제 요청을 처리했습니다."))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.paymentKey").value(payment.getPaymentKey()))
                .andExpect(jsonPath("$.data.transactionKey").value(payment.getApprovedTransactionKey()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.amount").value(64000))
                .andExpect(jsonPath("$.data.approvedAt").exists());

        verify(approvalService).confirm(orderId, AMOUNT, PaymentScenario.APPROVE);
    }

    @Test
    @DisplayName("주문번호 형식이 잘못되면 서비스를 호출하지 않는다")
    void confirm_rejectsInvalidOrderId() throws Exception {
        mockMvc.perform(post("/payments/confirm")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "invalid-order-id",
                                  "amount": 64000
                                }
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(
                approvalService,
                scenarioResolver
        );
    }

    private Payment completedPayment(String orderId) {
        Payment payment = new Payment(
                orderId,
                UUID.randomUUID().toString(),
                REQUEST_HASH,
                AMOUNT
        );

        payment.approve(
                UUID.randomUUID().toString(),
                LocalDateTime.of(
                        2026,
                        10,
                        12,
                        12,
                        0
                )
        );

        return payment;
    }
}
