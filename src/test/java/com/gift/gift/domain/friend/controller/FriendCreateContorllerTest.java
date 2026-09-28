package com.gift.gift.domain.friend.controller;

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

import com.gift.gift.domain.friend.dto.response.FriendCreateResponse;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.exception.FriendRequestExceptionHandler;
import com.gift.gift.domain.friend.service.FriendService;
import com.gift.gift.global.exception.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FriendCreateControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long FRIEND_USER_ID = 27L;

    private FriendService friendService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        friendService = mock(FriendService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new FriendController(friendService))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(
                        new FriendRequestExceptionHandler(),
                        new GlobalExceptionHandler()
                )
                .build();

        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
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
    @DisplayName("정상 요청이면 201과 친구 정보를 반환한다")
    void createFriend_returnsCreatedResponse() throws Exception {
        when(friendService.createFriend(
                any(),
                any()
        )).thenReturn(new FriendCreateResponse(
                FRIEND_USER_ID,
                "김민지"
        ));

        mockMvc.perform(post("/friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "friendUserId": 27
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message")
                        .value("김민지님을 친구로 추가했습니다."))
                .andExpect(jsonPath("$.data.friendUserId")
                        .value(27))
                .andExpect(jsonPath("$.data.friendName")
                        .value("김민지"))
                .andExpect(jsonPath("$.error")
                        .doesNotExist());

        verify(friendService).createFriend(
                org.mockito.ArgumentMatchers.eq(USER_ID),
                any()
        );
    }

    @Test
    @DisplayName("friendUserId가 없으면 400 INVALID_REQUEST를 반환한다")
    void createFriend_rejectsMissingFriendUserId() throws Exception {
        mockMvc.perform(post("/friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("추가할 사용자 정보를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("friendUserId"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("REQUIRED"))
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName("friendUserId가 양수가 아니면 400 INVALID_REQUEST를 반환한다")
    void createFriend_rejectsNonPositiveFriendUserId()
            throws Exception {

        mockMvc.perform(post("/friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "friendUserId": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("추가할 사용자 정보를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("friendUserId"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName("friendUserId 타입이 잘못되면 INVALID_FORMAT 상세를 반환한다")
    void createFriend_rejectsInvalidFriendUserIdType()
            throws Exception {

        mockMvc.perform(post("/friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "friendUserId": "invalid"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("추가할 사용자 정보를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field")
                        .value("friendUserId"))
                .andExpect(jsonPath("$.error.details[0].reason")
                        .value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName("대상 회원이 없으면 404와 친구 추가 대상 없음 메시지를 반환한다")
    void createFriend_returnsTargetNotFound() throws Exception {
        when(friendService.createFriend(any(), any()))
                .thenThrow(new FriendException(
                        FriendErrorCode.FRIEND_CREATE_TARGET_NOT_FOUND
                ));

        mockMvc.perform(post("/friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "friendUserId": 27
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("추가할 사용자를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.code")
                        .value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }

    @Test
    @DisplayName("친구 생성에 실패하면 500과 친구 추가 실패 메시지를 반환한다")
    void createFriend_returnsCreateFailed() throws Exception {
        when(friendService.createFriend(any(), any()))
                .thenThrow(new FriendException(
                        FriendErrorCode.FRIEND_CREATE_FAILED
                ));

        mockMvc.perform(post("/friends")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "friendUserId": 27
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("친구를 추가하지 못했습니다. 다시 시도해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
    }
}
