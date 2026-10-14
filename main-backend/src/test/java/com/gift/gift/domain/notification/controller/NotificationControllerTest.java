package com.gift.gift.domain.notification.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import com.gift.gift.domain.notification.dto.response.NotificationReadResponse;
import com.gift.gift.domain.notification.service.NotificationService;
import com.gift.gift.domain.notification.service.NotificationSseHub;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.support.TestValidatorFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationControllerTest {

    private static final Long USER_ID = 20L;

    private NotificationService notificationService;
    private NotificationSseHub notificationSseHub;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        notificationSseHub = mock(NotificationSseHub.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(notificationService, notificationSseHub))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(TestValidatorFactory.create())
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

    @Test
    @DisplayName("인증된 사용자의 알림 SSE 스트림을 연다")
    void subscribe_returnsEventStream() throws Exception {
        when(notificationSseHub.connect(USER_ID)).thenReturn(new SseEmitter());

        mockMvc.perform(get("/notifications/events"))
                .andExpect(status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andExpect(MockMvcResultMatchers.header().string("Cache-Control", "no-cache"))
                .andExpect(MockMvcResultMatchers.header().string("X-Accel-Buffering", "no"));

        verify(notificationSseHub).connect(USER_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("본인 알림을 읽음 처리하면 상태와 남은 미읽음 개수를 반환한다")
    void markAsRead_returnsReadResponse() throws Exception {
        when(notificationService.markAsRead(USER_ID, 10L))
                .thenReturn(new NotificationReadResponse(10L, true, 2L));

        mockMvc.perform(patch("/notifications/{notificationId}/read", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("알림을 읽음 처리했습니다."))
                .andExpect(jsonPath("$.data.notificationId").value(10))
                .andExpect(jsonPath("$.data.isRead").value(true))
                .andExpect(jsonPath("$.data.unreadCount").value(2))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(notificationService).markAsRead(USER_ID, 10L);
    }

    @Test
    @DisplayName("접근할 수 없는 알림은 404를 반환한다")
    void markAsRead_returnsNotFound_whenNotificationIsNotAccessible() throws Exception {
        when(notificationService.markAsRead(USER_ID, 10L))
                .thenThrow(new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND));

        mockMvc.perform(patch("/notifications/{notificationId}/read", 10L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("알림 ID가 양수가 아니면 400을 반환한다")
    void markAsRead_returnsBadRequest_whenNotificationIdIsInvalid() throws Exception {
        mockMvc.perform(patch("/notifications/{notificationId}/read", 0L)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
