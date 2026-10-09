package com.gift.gift.domain.auth.controller;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.auth.dto.response.TokenRefreshResponse;
import com.gift.gift.domain.auth.response.AuthSuccessCode;
import com.gift.gift.domain.auth.service.LogoutService;
import com.gift.gift.domain.auth.service.TokenRefreshService;
import com.gift.gift.domain.auth.support.TokenRefreshResult;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.RefreshCookieProvider;

@RestController
@RequiredArgsConstructor
public class AuthSessionController {

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

        AuthSuccessCode successCode =
                AuthSuccessCode.TOKEN_REFRESHED;

        return ResponseEntity
                .status(successCode.status())
                .header(
                        HttpHeaders.SET_COOKIE,
                        refreshCookie.toString()
                )
                .body(
                        ApiResponse.success(
                                successCode.message(),
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

        AuthSuccessCode successCode =
                AuthSuccessCode.LOGOUT_COMPLETED;

        return ResponseEntity
                .status(successCode.status())
                .header(
                        HttpHeaders.SET_COOKIE,
                        expiredCookie.toString()
                )
                .body(
                        ApiResponse.success(
                                successCode.message()
                        )
                );
    }
}
