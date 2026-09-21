package com.gift.gift.domain.user.controller;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.dto.response.TokenRefreshResponse;
import com.gift.gift.domain.user.service.LogoutService;
import com.gift.gift.domain.user.service.TokenRefreshService;
import com.gift.gift.domain.user.support.TokenRefreshResult;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.RefreshCookieProvider;

@RestController
@RequiredArgsConstructor
public class AuthSessionController {

    private static final String REFRESH_SUCCESS_MESSAGE =
            "토큰을 재발급했습니다.";

    private static final String LOGOUT_SUCCESS_MESSAGE =
            "로그아웃이 완료되었습니다.";

    private final TokenRefreshService tokenRefreshService;
    private final LogoutService logoutService;
    private final RefreshCookieProvider refreshCookieProvider;

    @PostMapping("/auth/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>>
    refresh(
            @CookieValue(
                    name = RefreshCookieProvider.COOKIE_NAME,
                    required = false
            )
            String refreshToken
    ) {
        TokenRefreshResult result =
                tokenRefreshService.refresh(
                        refreshToken
                );

        ResponseCookie refreshCookie =
                refreshCookieProvider.create(
                        result.refreshToken()
                );

        return ResponseEntity
                .ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie.toString()
                )
                .body(
                        ApiResponse.success(
                                REFRESH_SUCCESS_MESSAGE,
                                TokenRefreshResponse.from(
                                        result
                                )
                        )
                );
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<Map<String, Object>>>
    logout(
            @CookieValue(
                    name = RefreshCookieProvider.COOKIE_NAME,
                    required = false
            )
            String refreshToken
    ) {
        logoutService.logout(refreshToken);

        ResponseCookie expiredCookie =
                refreshCookieProvider.expire();

        return ResponseEntity
                .ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        expiredCookie.toString()
                )
                .body(
                        ApiResponse.success(
                                LOGOUT_SUCCESS_MESSAGE
                        )
                );
    }
}
