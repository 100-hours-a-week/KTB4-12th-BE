package com.gift.gift.domain.auth.controller;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gift.gift.domain.auth.service.LogoutService;
import com.gift.gift.domain.auth.service.TokenRefreshService;
import com.gift.gift.domain.auth.support.TokenRefreshResult;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.RefreshCookieProvider;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthSessionControllerTest {

    private static final String ALLOWED_ORIGIN =
            "http://localhost:3000";

    private static final String CURRENT_REFRESH_TOKEN =
            "a".repeat(43);

    private static final String NEW_REFRESH_TOKEN =
            "b".repeat(43);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TokenRefreshService tokenRefreshService;

    @MockitoBean
    private LogoutService logoutService;

    @Test
    @DisplayName("유효한 Cookie와 Origin으로 재발급하면 Access Token과 새 Cookie를 반환한다")
    void refresh_returnsAccessTokenAndRotatedCookie() throws Exception {
        when(tokenRefreshService.refresh(CURRENT_REFRESH_TOKEN))
                .thenReturn(new TokenRefreshResult(
                        "new-access-token",
                        3600,
                        NEW_REFRESH_TOKEN
                ));

        mockMvc.perform(post("/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(refreshCookie(CURRENT_REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("토큰을 재발급했습니다."))
                .andExpect(jsonPath("$.data.accessToken")
                        .value("new-access-token"))
                .andExpect(jsonPath("$.data.expiresIn")
                        .value(3600))
                .andExpect(jsonPath("$.data.refreshToken")
                        .doesNotExist())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString(
                                        "refreshToken="
                                                + NEW_REFRESH_TOKEN
                                ),
                                containsString("HttpOnly"),
                                containsString("Secure"),
                                containsString("SameSite=Lax"),
                                containsString("Path=/auth")
                        )
                ));
    }

    @Test
    @DisplayName("Refresh Cookie가 없으면 401을 반환하고 Cookie를 발급하지 않는다")
    void refresh_returnsUnauthorizedWhenCookieIsMissing()
            throws Exception {
        when(tokenRefreshService.refresh(isNull()))
                .thenThrow(new BusinessException(
                        ErrorCode.INVALID_REFRESH_TOKEN
                ));

        mockMvc.perform(post("/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REFRESH_TOKEN"))
                .andExpect(jsonPath("$.error.details")
                        .doesNotExist())
                .andExpect(header()
                        .doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("동시 재발급 락 충돌은 409를 반환하고 Cookie를 발급하지 않는다")
    void refresh_returnsConflictWhenLockCannotBeAcquired()
            throws Exception {
        when(tokenRefreshService.refresh(CURRENT_REFRESH_TOKEN))
                .thenThrow(new BusinessException(
                        ErrorCode.TOKEN_REFRESH_CONFLICT
                ));

        mockMvc.perform(post("/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(refreshCookie(CURRENT_REFRESH_TOKEN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code")
                        .value("TOKEN_REFRESH_CONFLICT"))
                .andExpect(header()
                        .doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("재발급 저장 실패는 500을 반환하고 Cookie를 발급하지 않는다")
    void refresh_doesNotSetCookieWhenPersistenceFails()
            throws Exception {
        when(tokenRefreshService.refresh(CURRENT_REFRESH_TOKEN))
                .thenThrow(new DataAccessResourceFailureException(
                        "session storage unavailable"
                ));

        mockMvc.perform(post("/auth/refresh")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(refreshCookie(CURRENT_REFRESH_TOKEN)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(header()
                        .doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("Cookie가 없는 로그아웃도 200과 만료 Cookie를 반환한다")
    void logout_isIdempotentAndExpiresCookieWhenCookieIsMissing()
            throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("로그아웃이 완료되었습니다."))
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        allOf(
                                containsString("refreshToken="),
                                containsString("Max-Age=0"),
                                containsString("HttpOnly"),
                                containsString("Secure"),
                                containsString("SameSite=Lax"),
                                containsString("Path=/auth")
                        )
                ));

        verify(logoutService).logout(null);
    }

    @Test
    @DisplayName("로그아웃 저장 실패는 500을 반환하고 Cookie를 만료시키지 않는다")
    void logout_doesNotExpireCookieWhenPersistenceFails()
            throws Exception {
        doThrow(new DataAccessResourceFailureException(
                "session storage unavailable"
        ))
                .when(logoutService)
                .logout(CURRENT_REFRESH_TOKEN);

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .cookie(refreshCookie(CURRENT_REFRESH_TOKEN)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(header()
                        .doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    @DisplayName("Origin이 없으면 서비스 호출 전에 403을 반환한다")
    void refresh_rejectsMissingOriginBeforeServiceCall()
            throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .cookie(refreshCookie(CURRENT_REFRESH_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code")
                        .value("CSRF_VALIDATION_FAILED"));

        verify(tokenRefreshService, never()).refresh(
                CURRENT_REFRESH_TOKEN
        );
    }

    @Test
    @DisplayName("허용되지 않은 Origin이면 로그아웃 서비스 호출 전에 403을 반환한다")
    void logout_rejectsDisallowedOriginBeforeServiceCall()
            throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .header(
                                HttpHeaders.ORIGIN,
                                "https://attacker.example"
                        )
                        .cookie(refreshCookie(CURRENT_REFRESH_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code")
                        .value("CSRF_VALIDATION_FAILED"));

        verify(logoutService, never()).logout(
                CURRENT_REFRESH_TOKEN
        );
    }

    private Cookie refreshCookie(String value) {
        return new Cookie(
                RefreshCookieProvider.COOKIE_NAME,
                value
        );
    }
}
