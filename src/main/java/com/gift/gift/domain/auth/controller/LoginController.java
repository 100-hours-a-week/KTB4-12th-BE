package com.gift.gift.domain.auth.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.auth.dto.request.LoginRequest;
import com.gift.gift.domain.auth.dto.response.LoginResponse;
import com.gift.gift.domain.auth.service.LoginService;
import com.gift.gift.domain.auth.support.LoginResult;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.RefreshCookieProvider;

@RestController
@RequiredArgsConstructor
public class LoginController {

    private static final String LOGIN_SUCCESS_MESSAGE =
            "로그인에 성공했습니다.";

    private final LoginService loginService;
    private final RefreshCookieProvider refreshCookieProvider;

    @PostMapping("/auth/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @CookieValue(
                    name = RefreshCookieProvider.COOKIE_NAME,
                    required = false
            )
            String existingRefreshToken,
            @Valid @RequestBody LoginRequest request
    ) {
        LoginResult result = loginService.login(
                request,
                existingRefreshToken
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
                                LOGIN_SUCCESS_MESSAGE,
                                LoginResponse.from(result)
                        )
                );
    }
}
