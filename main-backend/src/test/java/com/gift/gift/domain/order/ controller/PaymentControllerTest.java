package com.gift.gift.domain.order.controller;

import java.time.Clock;
import java.time.Instant;
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
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.service.PaymentQueryService;
import com.gift.gift.domain.order.service.PaymentService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentControllerTest {

    private static final Long USER_ID = 1L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private PaymentService paymentService;
    private PaymentQueryService paymentQueryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        paymentService = mock(PaymentService.class);
        paymentQueryService = mock(PaymentQueryService.class);

        Clock clock = Clock.fixed(
                         Instant.parse("2026-10-14T05:30:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        PaymentController controller = new PaymentController(
                paymentService,
                paymentQueryService,
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
    @DisplayName("인증 사용자의 결제 상태를 200 응답으로 반환한다")
    void getStatus_returnsPaymentStatus() throws Exception {
        PaymentStatusResponse response =
                new PaymentStatusResponse(
                        ORDER_ID,
                        OrderStatus.COMPLETED,
                        PaymentApprovalResult.APPROVED,
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
}
