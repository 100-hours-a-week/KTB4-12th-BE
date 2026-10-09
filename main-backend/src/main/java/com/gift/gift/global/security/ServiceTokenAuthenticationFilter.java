package com.gift.gift.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.gift.gift.global.exception.ErrorCode;

public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "AI_PROFILE_SERVICE";
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;
    private final SecurityErrorResponseWriter responseWriter;

    public ServiceTokenAuthenticationFilter(
            String serviceToken,
            SecurityErrorResponseWriter responseWriter
    ) {
        this.expectedToken = serviceToken.getBytes(StandardCharsets.UTF_8);
        this.responseWriter = responseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        List<String> authorizationHeaders = Collections.list(
                request.getHeaders(HttpHeaders.AUTHORIZATION)
        );

        if (authorizationHeaders.size() != 1
                || !isValidToken(authorizationHeaders.getFirst())) {
            SecurityContextHolder.clearContext();
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            responseWriter.write(response, ErrorCode.UNAUTHORIZED);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "ai-profiling-service",
                        null,
                        List.of(new SimpleGrantedAuthority(AUTHORITY))
                )
        );
        SecurityContextHolder.setContext(context);

        filterChain.doFilter(request, response);
    }

    private boolean isValidToken(String authorization) {
        if (!authorization.regionMatches(
                true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length()
        )) {
            return false;
        }

        byte[] actualToken = authorization.substring(BEARER_PREFIX.length())
                .getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expectedToken, actualToken);
    }
}
