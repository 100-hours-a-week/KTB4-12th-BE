package com.gift.gift.domain.recommendation.chat.web;

import java.io.IOException;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;
import com.gift.gift.domain.recommendation.chat.service.ChatSseService;
import com.gift.gift.domain.recommendation.chat.service.ChatSseService.Session;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSseRelay {

    private final ChatSseService service;
    private final ChatSseProperties properties;

    public void start(
            HttpServletRequest request,
            HttpServletResponse response,
            Session session
    ) {
        AsyncContext async;

        try {
            async = request.startAsync(request, response);
            async.setTimeout(properties.totalTimeout().toMillis());

            async.addListener(new AsyncListener() {

                @Override
                public void onComplete(AsyncEvent event) {
                    session.close();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    finish(async, session);
                }

                @Override
                public void onError(AsyncEvent event) {
                    finish(async, session);
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                }
            });
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }

        String traceId = MDC.get("traceId");

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        try {
            // 빈 스트림도 200으로 응답하고, 이후 오류는 연결 종료로 처리한다.
            response.flushBuffer();

            service.execute(session, () -> {
                try {
                    session.transferTo(response.getOutputStream());
                } catch (IOException | RuntimeException exception) {
                    log.info("Chat SSE relay ended: traceId={}", traceId);
                } finally {
                    finish(async, session);
                }
            });
        } catch (IOException | RuntimeException exception) {
            finish(async, session);
        }
    }

    private static void finish(AsyncContext async, Session session) {
        try {
            session.close();
        } finally {
            try {
                async.complete();
            } catch (IllegalStateException ignored) {
                // timeout·오류·EOF가 겹쳐 이미 완료된 요청일 수 있다.
            }
        }
    }
}
