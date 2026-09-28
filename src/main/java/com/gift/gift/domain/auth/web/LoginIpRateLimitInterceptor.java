package com.gift.gift.domain.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.gift.gift.domain.auth.service.LoginRateLimiter;

@Component
@RequiredArgsConstructor
public class LoginIpRateLimitInterceptor
        implements HandlerInterceptor {

    private final LoginRateLimiter loginRateLimiter;

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        loginRateLimiter.checkIpAttempt(
                request.getRemoteAddr()
        );

        return true;
    }
}
