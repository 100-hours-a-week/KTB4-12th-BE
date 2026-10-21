package com.gift.payment.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.global.response.ApiErrorResponse;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private static final String TRACE_ID_KEY = "traceId";

    private final byte[] expectedKey;
    private final ObjectMapper objectMapper;

    public InternalApiKeyFilter(InternalApiKeyProperties properties, ObjectMapper objectMapper) {
        this.expectedKey = properties.key().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        return !path.equals("/payments") && !path.startsWith("/payments/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String providedKey = resolveKey(request);

        if (matches(providedKey)) {
            filterChain.doFilter(request, response);
            return;
        }

        reject(request, response);
    }

    private String resolveKey(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(API_KEY_HEADER);

        if (!values.hasMoreElements()) {
            return null;
        }

        String key = values.nextElement();

        if (values.hasMoreElements()) {
            return null;
        }

        return key;
    }

    private boolean matches(String providedKey) {
        if (providedKey == null) {
            return false;
        }

        byte[] provided = providedKey.getBytes(StandardCharsets.UTF_8);

        return MessageDigest.isEqual(expectedKey, provided);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        ErrorCode errorCode = ErrorCode.INVALID_INTERNAL_API_KEY;
        String traceId = resolveTraceId();

        log.warn("Payment Server 내부 API 인증에 실패했습니다. " + "traceId={}, method={}, path={}",
                traceId,
                request.getMethod(),
                request.getRequestURI()
        );

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        objectMapper.writeValue(
                response.getWriter(),
                ApiErrorResponse.from(errorCode, traceId)
        );
    }

    private String resolveTraceId() {
        String traceId = MDC.get(TRACE_ID_KEY);

        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }

        return UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}
