package com.gift.gift.domain.order.controller;

import java.time.Clock;
import java.time.Instant;
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

import com.gift.gift.domain.order.dto.response.PendingPaymentOrderResponse;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.service.PaymentCancellationService;
import com.gift.gift.domain.order.service.PaymentQueryService;
import com.gift.gift.domain.order.service.PaymentService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PendingPaymentControllerTest {

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
        paymentCancellationService = mock(PaymentCancellationService.class);

        PaymentController controller = new PaymentController(
                paymentService,
                paymentQueryService,
                paymentCancellationService,
                Clock.fixed(
                        Instant.parse("2026-10-14T05:30:00Z"),
                        ZoneId.of("Asia/Seoul")
                )
        );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("https://test-issuer.example")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(), USER_ID.toString())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("인증 사용자의 미확정 결제 목록을 반환한다")
    void getPendingPayments_returnsOrdersForAuthenticatedUser() throws Exception {
        when(paymentQueryService.getPendingPayments(USER_ID))
                .thenReturn(List.of(
                        new PendingPaymentOrderResponse(
                                ORDER_ID,
                                OrderStatus.PENDING
                        )
                ));

        mockMvc.perform(get("/payments/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data[0].paymentStatus").value("PENDING"));

        verify(paymentQueryService).getPendingPayments(USER_ID);
        verifyNoInteractions(paymentService, paymentCancellationService);
    }

    @Test
    @DisplayName("미확정 주문이 없으면 빈 배열을 반환한다")
    void getPendingPayments_returnsEmptyArrayWhenNoPendingOrders() throws Exception {
        when(paymentQueryService.getPendingPayments(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/payments/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(paymentQueryService).getPendingPayments(USER_ID);
    }
}
