package com.gift.gift.global.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.gift.gift.global.exception.ErrorCode;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint
        implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter responseWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        if (exception instanceof
                InternalAuthenticationServiceException) {
            log.error("인증된 사용자 조회에 실패했습니다.");

            responseWriter.write(
                    response,
                    ErrorCode.INTERNAL_SERVER_ERROR
            );
            return;
        }

        response.setHeader("WWW-Authenticate", "Bearer");

        responseWriter.write(
                response,
                ErrorCode.UNAUTHORIZED
        );
    }
}
