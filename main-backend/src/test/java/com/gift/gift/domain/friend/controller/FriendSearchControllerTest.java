package com.gift.gift.domain.friend.controller;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.exception.FriendErrorCode;
import com.gift.gift.domain.friend.exception.FriendException;
import com.gift.gift.domain.friend.service.FriendService;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FriendSearchControllerTest {

    private static final Long USER_ID = 1L;

    private FriendService friendService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        friendService = mock(FriendService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new FriendController(friendService)
                )
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
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("https://test-issuer.example")
                .build();

        SecurityContextHolder
                .getContext()
                .setAuthentication(
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
    @DisplayName(
            "검색어를 정규화하여 친구를 검색하고 200과 검색 결과를 반환한다"
    )
    void searchFriends_returnsSearchResult() throws Exception {
        CursorPageResponse<FriendListItem> page =
                CursorPageResponse.from(
                        List.of(
                                new FriendListItem(
                                        31L,
                                        27L,
                                        "MinSeo",
                                        "minseo@example.com",
                                        "2000-03-14"
                                )
                        ),
                        "next-cursor",
                        true
                );

        when(friendService.searchFriends(
                USER_ID,
                "minseo",
                null
        )).thenReturn(page);

        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "  MinSeo  ")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("친구 검색 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.friends.length()")
                        .value(1))
                .andExpect(jsonPath("$.data.friends[0].friendId")
                        .value(31))
                .andExpect(jsonPath("$.data.friends[0].userId")
                        .value(27))
                .andExpect(jsonPath("$.data.friends[0].name")
                        .value("MinSeo"))
                .andExpect(jsonPath("$.data.friends[0].email")
                        .value("minseo@example.com"))
                .andExpect(jsonPath("$.data.friends[0].birth")
                        .value("2000-03-14"))
                .andExpect(jsonPath("$.data.pagination.nextCursor")
                        .value("next-cursor"))
                .andExpect(jsonPath("$.data.pagination.hasNext")
                        .value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(friendService).searchFriends(
                USER_ID,
                "minseo",
                null
        );
        verifyNoMoreInteractions(friendService);
    }

    @Test
    @DisplayName(
            "검색 결과가 없으면 200과 빈 친구 목록을 반환한다"
    )
    void searchFriends_returnsEmptyResult() throws Exception {
        when(friendService.searchFriends(
                USER_ID,
                "없는친구",
                null
        )).thenReturn(
                CursorPageResponse.from(
                        List.of(),
                        null,
                        false
                )
        );

        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "없는친구")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("친구 검색 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.friends").isEmpty())
                .andExpect(jsonPath("$.data.pagination.nextCursor")
                        .value(nullValue()))
                .andExpect(jsonPath("$.data.pagination.hasNext")
                        .value(false))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(friendService).searchFriends(
                USER_ID,
                "없는친구",
                null
        );
    }

    @Test
    @DisplayName(
            "검색어가 누락되면 Service를 호출하지 않고 400을 반환한다"
    )
    void searchFriends_rejectsMissingQuery() throws Exception {
        mockMvc.perform(
                        get("/friends/search")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("검색어를 입력해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @ParameterizedTest(name = "[{index}] query=\"{0}\"")
    @ValueSource(strings = {
            "",
            " ",
            "   "
    })
    @DisplayName(
            "검색어가 빈 값 또는 공백이면 Service를 호출하지 않고 400을 반환한다"
    )
    void searchFriends_rejectsBlankQuery(
            String query
    ) throws Exception {
        mockMvc.perform(
                        get("/friends/search")
                                .param("query", query)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("검색어를 입력해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName(
            "허용되지 않은 Query Parameter가 있으면 400을 반환한다"
    )
    void searchFriends_rejectsUnsupportedParameter() throws Exception {
        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "김")
                                .param("size", "10")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("검색어를 입력해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName(
            "검색어가 중복 전달되면 400을 반환한다"
    )
    void searchFriends_rejectsDuplicatedQuery() throws Exception {
        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "김", "이")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("검색어를 입력해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist());

        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName(
            "커서가 중복 전달되면 400을 반환한다"
    )
    void searchFriends_rejectsDuplicatedCursor() throws Exception {
        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "김")
                                .param(
                                        "cursor",
                                        "cursor-1",
                                        "cursor-2"
                                )
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("페이지 정보를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());
        verifyNoInteractions(friendService);
    }

    @Test
    @DisplayName(
            "유효하지 않은 커서는 400 INVALID_CURSOR로 응답한다"
    )
    void searchFriends_returnsInvalidCursor() throws Exception {
        when(friendService.searchFriends(
                USER_ID,
                "김",
                "broken"
        )).thenThrow(new InvalidCursorException());

        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "김")
                                .param("cursor", "broken")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("페이지 정보를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verify(friendService).searchFriends(
                USER_ID,
                "김",
                "broken"
        );
    }

    @Test
    @DisplayName(
            "검색 처리 내부 오류는 500 검색 실패 응답으로 변환한다"
    )
    void searchFriends_returnsInternalServerError() throws Exception {
        when(friendService.searchFriends(
                USER_ID,
                "김",
                null
        )).thenThrow(
                new FriendException(
                        FriendErrorCode.FRIEND_SEARCH_FAILED,
                        new IllegalStateException(
                                "테스트용 검색 처리 오류"
                        )
                )
        );

        mockMvc.perform(
                        get("/friends/search")
                                .param("query", "김")
                )
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value(
                                "친구 검색에 실패했습니다. "
                                        + "다시 시도해 주세요."
                        ))
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId")
                        .isNotEmpty())
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(jsonPath("$.data")
                        .doesNotExist());

        verify(friendService).searchFriends(
                USER_ID,
                "김",
                null
        );
    }
}
