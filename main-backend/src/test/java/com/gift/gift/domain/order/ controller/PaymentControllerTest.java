package com.gift.gift.domain.order.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.order.dto.response.PaymentStatusResponse;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.service.PaymentCancellationResult;
import com.gift.gift.domain.order.service.PaymentCancellationService;
import com.gift.gift.domain.order.service.PaymentQueryService;
import com.gift.gift.domain.order.service.PaymentProcessingResult;
import com.gift.gift.domain.order.service.PaymentResult;
import com.gift.gift.domain.order.service.PaymentService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerTest {

    private static final Long USER_ID = 1L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private PaymentService paymentService;
    private PaymentQueryService paymentQueryService;
    private PaymentCancellationService paymentCancellationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        paymentQueryService = mock(PaymentQueryService.class);
        paymentCancellationService = mock(
                PaymentCancellationService.class
        );

        Clock clock = Clock.fixed(
                         Instant.parse("2026-10-14T05:30:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        PaymentController controller = new PaymentController(
                paymentService,
                paymentQueryService,
                paymentCancellationService,
                clock
        );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(
                        new GlobalExceptionHandler()
                )
                .build();

        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(
                        Instant.now().plusSeconds(3600)
                )
                .issuer("https://test-issuer.example")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of(),
                        USER_ID.toString()
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("승인 결과가 처리 중이면 재조회 간격과 함께 202를 반환한다")
    void create_returnsAcceptedWhenPaymentIsProcessing() throws Exception {
        when(paymentService.pay(
                eq(USER_ID),
                eq("payment-request-001"),
                isNull(),
                any()
        )).thenReturn(PaymentResult.processing(
                new PaymentProcessingResult(
                        ORDER_ID,
                        PaymentApprovalResult.UNKNOWN
                ),
                true
        ));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "payment-request-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "productId": 101,
                                  "recipientUserId": 27,
                                  "quantity": 2,
                                  "expectedUnitPrice": 32000
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.message").value(
                        "결제를 처리하고 있습니다. 잠시 후 다시 확인해 주세요."
                ))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.approvalResult").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(5))
                .andExpect(jsonPath("$.data.paymentKey").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(paymentService).pay(
                eq(USER_ID),
                eq("payment-request-001"),
                isNull(),
                any()
        );
    }

    @Test
    @DisplayName("승인은 완료되고 로컬 처리가 진행 중이면 확정 메시지와 함께 202를 반환한다")
    void create_returnsAcceptedWhenLocalPaymentIsProcessing() throws Exception {
        when(paymentService.pay(
                eq(USER_ID),
                eq("payment-request-001"),
                isNull(),
                any()
        )).thenReturn(PaymentResult.processing(
                new PaymentProcessingResult(
                        ORDER_ID,
                        PaymentApprovalResult.APPROVED
                ),
                true
        ));

        mockMvc.perform(post("/payments")
                        .header("Idempotency-Key", "payment-request-001")
                        .contentType("application/json")
                        .content("""
                                {
                                  "productId": 101,
                                  "recipientUserId": 27,
                                  "quantity": 2,
                                  "expectedUnitPrice": 32000
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.message").value(
                        "결제는 승인되었으며 주문 처리를 완료하고 있습니다. 완료되면 알려드리겠습니다."
                ))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.approvalResult").value("APPROVED"))
                .andExpect(jsonPath("$.data.retryAfterSeconds").value(5));
    }

    @Test
    @DisplayName("인증 사용자의 결제 상태를 200 응답으로 반환한다")
    void getStatus_returnsPaymentStatus() throws Exception {
        PaymentStatusResponse response =
                new PaymentStatusResponse(
                        ORDER_ID,
                        OrderStatus.COMPLETED,
                        PaymentApprovalResult.APPROVED,
                        null,
                        501L,
                        GiftStatus.WAITING_FOR_ACCEPTANCE,
                        64_000L,
                        OffsetDateTime.parse(
                                "2026-10-14T14:30:00+09:00"
                        ),
                        null
                );

        when(paymentQueryService.getStatus(
                USER_ID,
                ORDER_ID
        )).thenReturn(response);

        mockMvc.perform(get(
                        "/payments/{orderId}",
                        ORDER_ID
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("결제 상태를 조회했습니다."))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.paymentStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.approvalResult").value("APPROVED"))
                .andExpect(jsonPath("$.data.giftId").value(501))
                .andExpect(jsonPath("$.data.giftStatus").value("WAITING_FOR_ACCEPTANCE"))
                .andExpect(jsonPath("$.data.amount").value(64_000))
                .andExpect(jsonPath("$.data.approvedAt").value("2026-10-14T14:30:00+09:00"))
                .andExpect(jsonPath("$.data.canceledAt").value((Object) null))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(paymentQueryService).getStatus(USER_ID, ORDER_ID);
        verifyNoInteractions(paymentService);
        verifyNoMoreInteractions(paymentQueryService);
    }

    @Test
    @DisplayName("운영 확인이 필요한 결제는 새로고침 상태 응답에 실패 코드를 반환한다")
    void getStatus_returnsReconciliationRequiredFailureCode() throws Exception {
        PaymentStatusResponse response = new PaymentStatusResponse(
                ORDER_ID,
                OrderStatus.PENDING,
                PaymentApprovalResult.UNKNOWN,
                OrderFailureCode.PAYMENT_RECONCILIATION_REQUIRED,
                null,
                null,
                64_000L,
                null,
                null
        );
        when(paymentQueryService.getStatus(USER_ID, ORDER_ID)).thenReturn(response);

        mockMvc.perform(get("/payments/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.approvalResult").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.failureCode")
                        .value("PAYMENT_RECONCILIATION_REQUIRED"));
    }

    @Test
    @DisplayName("잘못된 주문번호 형식은 details 없이 400을 반환한다")
    void getStatus_returnsInvalidRequest_whenOrderIdIsMalformed() throws Exception {

        mockMvc.perform(get("/payments/{orderId}", "invalid-order-id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        verifyNoInteractions(paymentService, paymentQueryService);
    }

    @Test
    @DisplayName("접근 가능한 결제가 없으면 404를 반환한다")
    void getStatus_returnsNotFound_whenPaymentIsNotAccessible() throws Exception {

        when(paymentQueryService.getStatus(USER_ID, ORDER_ID)).
                thenThrow(new OrderException(OrderErrorCode.PAYMENT_NOT_FOUND));

        mockMvc.perform(get("/payments/{orderId}", ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("결제 정보를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.code").value("PAYMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(paymentQueryService).getStatus(USER_ID, ORDER_ID);
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("최초 결제 취소 완료는 201 응답을 반환한다")
    void cancel_returnsCreated_whenCancellationIsNew() throws Exception {
        when(paymentCancellationService.cancel(
                eq(USER_ID),
                eq(ORDER_ID),
                eq("cancel-request-001"),
                any()
        )).thenReturn(cancellationResult(true));

        mockMvc.perform(post(
                        "/payments/{orderId}/cancellations",
                        ORDER_ID
                ).header(
                        "Idempotency-Key",
                        "cancel-request-001"
                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message")
                        .value("결제와 선물을 취소했습니다."))
                .andExpect(jsonPath("$.data.orderId")
                        .value(ORDER_ID))
                .andExpect(jsonPath("$.data.paymentStatus")
                        .value("CANCELED"))
                .andExpect(jsonPath("$.data.giftStatus")
                        .value("CANCELED"))
                .andExpect(jsonPath("$.data.cancelAmount")
                        .value(64_000))
                .andExpect(jsonPath("$.data.balanceAfter")
                        .value(500_000))
                .andExpect(jsonPath("$.data.canceledAt")
                        .value("2026-10-15T12:00:00+09:00"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(paymentCancellationService).cancel(
                eq(USER_ID),
                eq(ORDER_ID),
                eq("cancel-request-001"),
                any()
        );
    }

    @Test
    @DisplayName("완료된 동일 결제 취소 요청은 200 응답을 반환한다")
    void cancel_returnsOk_whenCancellationIsReused() throws Exception {
        when(paymentCancellationService.cancel(
                eq(USER_ID),
                eq(ORDER_ID),
                eq("cancel-request-001"),
                any()
        )).thenReturn(cancellationResult(false));

        mockMvc.perform(post(
                        "/payments/{orderId}/cancellations",
                        ORDER_ID
                ).header(
                        "Idempotency-Key",
                        "cancel-request-001"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("결제와 선물을 취소했습니다."))
                .andExpect(jsonPath("$.data.orderId")
                        .value(ORDER_ID))
                .andExpect(jsonPath("$.data.paymentStatus")
                        .value("CANCELED"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    private PaymentCancellationResult cancellationResult(
            boolean created
    ) {
        return new PaymentCancellationResult(
                ORDER_ID,
                "7d9f6d7e-a34c-4bbc-97d6-33a2b43c183a",
                "542f1507-e235-47c2-93c1-a30ed5b31d49",
                OrderStatus.CANCELED,
                501L,
                GiftStatus.CANCELED,
                64_000L,
                500_000L,
                LocalDateTime.of(2026, 10, 15, 12, 0),
                created
        );
    }
}
