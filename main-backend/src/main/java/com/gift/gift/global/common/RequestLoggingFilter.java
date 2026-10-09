package com.gift.gift.global.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_KEY = "traceId";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        long startTime = System.nanoTime();
        MDC.put(TRACE_ID_KEY, createTraceId());

        try {
            filterChain.doFilter(request, response);
        } finally {
            logRequest(request, response, elapsedMillis(startTime));
            MDC.remove(TRACE_ID_KEY);
        }
    }

    private void logRequest(
        HttpServletRequest request,
        HttpServletResponse response,
        long durationMillis
    ) {
        int status = response.getStatus();

        if (status >= 500) {
            log.error(
                "HTTP request failed. method={}, path={}, status={}, durationMs={}",
                request.getMethod(),
                resolvePathPattern(request),
                status,
                durationMillis
            );
            return;
        }

        if (status >= 400) {
            log.warn(
                "HTTP request rejected. method={}, path={}, status={}, durationMs={}",
                request.getMethod(),
                resolvePathPattern(request),
                status,
                durationMillis
            );
            return;
        }

        log.info(
            "HTTP request completed. method={}, path={}, status={}, durationMs={}",
            request.getMethod(),
            resolvePathPattern(request),
            status,
            durationMillis
        );
    }

    private long elapsedMillis(long startTime) {
        return (System.nanoTime() - startTime) / 1_000_000;
    }

    private String resolvePathPattern(HttpServletRequest request) {
        Object pathPattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pathPattern instanceof String pattern ? pattern : "UNMATCHED";
    }

    private String createTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
