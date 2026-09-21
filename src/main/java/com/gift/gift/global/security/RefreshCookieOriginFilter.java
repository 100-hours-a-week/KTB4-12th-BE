package com.gift.gift.global.security;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gift.gift.global.exception.ErrorCode;

@Component
@RequiredArgsConstructor
public class RefreshCookieOriginFilter
        extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PATHS =
            Set.of(
                    "/auth/refresh",
                    "/auth/logout"
            );

    private final CorsProperties corsProperties;
    private final SecurityErrorResponseWriter responseWriter;

    @Override
    protected boolean shouldNotFilter(
            HttpServletRequest request
    ) {
        String requestPath = request.getRequestURI()
                .substring(request.getContextPath().length());

        return !HttpMethod.POST.matches(
                request.getMethod()
        ) || !PROTECTED_PATHS.contains(
                requestPath
        );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String origin = request.getHeader(
                HttpHeaders.ORIGIN
        );

        if (origin == null
                || !corsProperties
                .allowedOrigins()
                .contains(origin)) {
            responseWriter.write(
                    response,
                    ErrorCode.CSRF_VALIDATION_FAILED
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
