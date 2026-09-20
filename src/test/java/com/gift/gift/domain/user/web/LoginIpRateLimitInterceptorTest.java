package com.gift.gift.domain.user.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.user.exception.LoginRateLimitExceededException;
import com.gift.gift.domain.user.service.LoginRateLimiter;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoginIpRateLimitInterceptorTest {

    private LoginRateLimiter loginRateLimiter;
    private LoginIpRateLimitInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        loginRateLimiter = mock(LoginRateLimiter.class);
        interceptor = new LoginIpRateLimitInterceptor(loginRateLimiter);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    @Test
    @DisplayName("로그인 POST 요청은 DTO 검증 전에 원격 IP의 토큰을 차감한다")
    void preHandle_consumesIpTokenBeforeControllerValidation() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("203.0.113.10");

        boolean permitted = interceptor.preHandle(
                request,
                response,
                new Object()
        );

        assertTrue(permitted);
        verify(loginRateLimiter).checkIpAttempt("203.0.113.10");
    }

    @Test
    @DisplayName("IP 요청 제한은 Controller와 DTO 검증에 도달하기 전에 예외를 발생시킨다")
    void preHandle_rejectsLimitedIpBeforeControllerValidation() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("203.0.113.11");

        org.mockito.Mockito.doThrow(
                new LoginRateLimitExceededException(6)
        ).when(loginRateLimiter).checkIpAttempt("203.0.113.11");

        assertThrows(
                LoginRateLimitExceededException.class,
                () -> interceptor.preHandle(
                        request,
                        response,
                        new Object()
                )
        );
    }

    @Test
    @DisplayName("로그인 경로의 POST가 아닌 요청은 IP 토큰을 차감하지 않는다")
    void preHandle_skipsNonPostRequest() {
        when(request.getMethod()).thenReturn("GET");

        boolean permitted = interceptor.preHandle(
                request,
                response,
                new Object()
        );

        assertTrue(permitted);
        verify(loginRateLimiter, never()).checkIpAttempt(
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("Servlet이 제공한 remoteAddr 값을 변경하지 않고 제한기에 전달한다")
    void preHandle_passesServletRemoteAddressToRateLimiter() {
        when(request.getMethod()).thenReturn("POST");
        when(request.getRemoteAddr()).thenReturn("2001:db8::10");

        interceptor.preHandle(request, response, new Object());

        verify(loginRateLimiter).checkIpAttempt("2001:db8::10");
    }
}
