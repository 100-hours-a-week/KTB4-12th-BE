package com.gift.gift.domain.friend.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.entity.FriendRequestStatus;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.exception.FriendRequestExceptionHandler;
import com.gift.gift.domain.friend.service.FriendRequestService;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FriendRequestControllerTest {
    private FriendRequestService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FriendRequestService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FriendRequestController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new FriendRequestExceptionHandler(), new GlobalExceptionHandler()).build();
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("정상 요청은 201과 PENDING 요청 정보를 반환한다")
    void create_returnsPendingResponse() throws Exception {
        when(service.create(any(), any())).thenReturn(new FriendRequestResponse(10L, FriendRequestStatus.PENDING,
                1L, "요청자", 2L, "수신자", LocalDateTime.of(2026, 10, 8, 12, 0)));
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":2}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.requestId").value(10))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.requesterId").value(1))
                .andExpect(jsonPath("$.data.receiverId").value(2)).andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("receiverId가 없으면 400과 REQUIRED 필드 상세를 반환한다")
    void create_rejectsMissingReceiver() throws Exception {
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("receiverId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "\"invalid\"", "[]"})
    @DisplayName("잘못된 receiverId 형식은 400과 INVALID_FORMAT 상세를 반환한다")
    void create_rejectsInvalidReceiver(String receiver) throws Exception {
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":" + receiver + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("receiverId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("INVALID_FORMAT"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("역방향 요청은 요청 ID 없이 409를 반환한다")
    void create_returnsExistingConverseId() throws Exception {
        when(service.create(any(), any())).thenThrow(new FriendException(FriendErrorCode.CONVERSE_REQUEST_EXISTS));
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONVERSE_REQUEST_EXISTS"))
                .andExpect(jsonPath("$.error.requestId").doesNotExist())
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("없는 대상 오류는 404이며 requestId 필드를 추가하지 않는다")
    void create_returnsNotFoundWithoutRequestId() throws Exception {
        when(service.create(any(), any())).thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":2}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(ErrorCode.USER_NOT_FOUND.message()))
                .andExpect(jsonPath("$.error.requestId").doesNotExist());
    }

    @Test
    @DisplayName("생성 실패는 500과 친구 요청 실패 메시지를 반환한다")
    void create_returnsRequestFailure() throws Exception {
        when(service.create(any(), any())).thenThrow(new FriendException(FriendErrorCode.FRIEND_REQUEST_CREATE_FAILED));
        mvc.perform(post("/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{\"receiverId\":2}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("친구 요청을 보내지 못했습니다. 다시 시도해 주세요."));
    }
}
