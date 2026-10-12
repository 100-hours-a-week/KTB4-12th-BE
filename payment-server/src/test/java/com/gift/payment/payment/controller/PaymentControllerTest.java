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
import com.gift.payment.payment.service.PaymentQueryService;
import com.gift.payment.payment.support.PaymentScenario;
import com.gift.payment.payment.support.PaymentScenarioResolver;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentControllerTest {

    private static final long AMOUNT = 64_000L;
    private static final String REQUEST_HASH = "a".repeat(64);

    private PaymentApprovalService approvalService;
    private PaymentQueryService queryService;
    private PaymentScenarioResolver scenarioResolver;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        approvalService = mock(PaymentApprovalService.class);
        queryService = mock(PaymentQueryService.class);
        scenarioResolver = mock(PaymentScenarioResolver.class);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new PaymentController(
                                approvalService,
                                queryService,
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

        when(scenarioResolver.resolve("APPROVE")).thenReturn(PaymentScenario.APPROVE);
        when(approvalService.confirm(orderId, AMOUNT, PaymentScenario.APPROVE)).thenReturn(payment);

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
    @DisplayName("주문번호 형식이 잘못되면 승인 서비스를 호출하지 않는다")
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

        verifyNoInteractions(approvalService, queryService, scenarioResolver);
    }

    @Test
    @DisplayName("주문번호로 처리 중인 결제 상태를 조회한다")
    void getByOrderId_returnsPendingPayment()
            throws Exception {
        String orderId = UUID.randomUUID().toString();
        Payment payment = payment(orderId);

        when(queryService.getByOrderId(orderId))
                .thenReturn(payment);

        mockMvc.perform(get("/payments/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("결제 상태를 조회했습니다."))
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.paymentKey").value(payment.getPaymentKey()))
                .andExpect(jsonPath("$.data.transactionKey").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.amount").value(64000))
                .andExpect(jsonPath("$.data.approvedAt").value(nullValue()));

        verify(queryService).getByOrderId(orderId);
        verifyNoInteractions(approvalService, scenarioResolver);
    }

    private Payment payment(String orderId) {
        return new Payment(
                orderId,
                UUID.randomUUID().toString(),
                REQUEST_HASH,
                AMOUNT
        );
    }

    private Payment completedPayment(String orderId) {
        Payment payment = payment(orderId);

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
