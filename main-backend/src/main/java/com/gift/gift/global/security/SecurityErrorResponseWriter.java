package com.gift.gift.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.response.ApiResponse;

@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public void write(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {
        String traceId = MDC.get("traceId");

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID()
                    .toString()
                    .replace("-", "");
        }

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );

        response.getWriter().write(
                objectMapper.writeValueAsString(
                        ApiResponse.error(
                                errorCode,
                                errorCode.message(),
                                traceId
                        )
                )
        );
    }
}
