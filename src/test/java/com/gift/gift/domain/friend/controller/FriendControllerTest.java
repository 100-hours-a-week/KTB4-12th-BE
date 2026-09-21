package com.gift.gift.domain.friend.controller;

import java.time.Instant;
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

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.service.FriendService;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FriendControllerTest {

    private static final Long USER_ID = 1L;

    private FriendService friendService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        friendService = mock(FriendService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new FriendController(friendService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
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
    @DisplayName("친구 목록은 인증 사용자와 커서로 조회하고 항목과 페이지 정보를 반환한다")
    void getFriends_returnsPage_forAuthenticatedUser() throws Exception {
        when(friendService.getFriends(USER_ID, "cursor-1")).thenReturn(CursorPageResponse.from(
                List.of(new FriendListItem(31L, 27L, "김민지", "minji@example.com", "03-14")),
                "next-cursor",
                true
        ));

        mockMvc.perform(get("/friends").param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("친구 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].friendId").value(31))
                .andExpect(jsonPath("$.data.items[0].userId").value(27))
                .andExpect(jsonPath("$.data.items[0].name").value("김민지"))
                .andExpect(jsonPath("$.data.items[0].email").value("minji@example.com"))
                .andExpect(jsonPath("$.data.items[0].birth").value("03-14"))
                .andExpect(jsonPath("$.data.pagination.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(friendService).getFriends(USER_ID, "cursor-1");
        verifyNoMoreInteractions(friendService);
    }

    @Test
    @DisplayName("커서가 없으면 첫 페이지로 조회한다")
    void getFriends_queriesFirstPage_whenCursorIsMissing() throws Exception {
        when(friendService.getFriends(USER_ID, null))
                .thenReturn(CursorPageResponse.from(List.of(), null, false));

        mockMvc.perform(get("/friends"))
                .andExpect(status().isOk());

        verify(friendService).getFriends(USER_ID, null);
    }

    @Test
    @DisplayName("생일을 공개하지 않은 친구의 birth는 null로 응답한다")
    void getFriends_returnsNullBirth_whenBirthdayIsPrivate() throws Exception {
        when(friendService.getFriends(USER_ID, null)).thenReturn(CursorPageResponse.from(
                List.of(new FriendListItem(31L, 27L, "김민지", "minji@example.com", null)),
                null,
                false
        ));

        mockMvc.perform(get("/friends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].birth").value(nullValue()));
    }

    @Test
    @DisplayName("친구가 없으면 200과 빈 배열, 마지막 페이지 정보를 반환한다")
    void getFriends_returnsEmptyLastPage_whenNoFriends() throws Exception {
        when(friendService.getFriends(USER_ID, null))
                .thenReturn(CursorPageResponse.from(List.of(), null, false));

        mockMvc.perform(get("/friends"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("친구 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.pagination.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(false));
    }

    @Test
    @DisplayName("잘못된 커서는 details 없는 400 INVALID_CURSOR로 응답한다")
    void getFriends_returnsInvalidCursor_whenCursorIsInvalid() throws Exception {
        when(friendService.getFriends(USER_ID, "broken")).thenThrow(new InvalidCursorException());

        mockMvc.perform(get("/friends").param("cursor", "broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("빈 문자열 커서는 첫 페이지로 바꾸지 않고 그대로 전달해 INVALID_CURSOR로 응답한다")
    void getFriends_passesBlankCursorAsIs() throws Exception {
        when(friendService.getFriends(USER_ID, "")).thenThrow(new InvalidCursorException());

        mockMvc.perform(get("/friends").param("cursor", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));

        verify(friendService).getFriends(USER_ID, "");
    }
}
