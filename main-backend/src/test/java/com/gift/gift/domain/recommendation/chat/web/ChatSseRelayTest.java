package com.gift.gift.domain.recommendation.chat.web;

import java.time.Duration;
import java.io.IOException;
import jakarta.servlet.ServletOutputStream;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;
import com.gift.gift.domain.recommendation.chat.service.ChatSseService;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSseRelayTest {
    @Test
    @DisplayName("전체 timeout은 idle과 별도로 240초이며 timeout·오류에서 중계 자원과 응답을 종료한다")
    void start_registersTotalDeadlineAndCleanupCallbacks() throws Exception {
        ChatSseService service = mock(ChatSseService.class);
        ChatSseService.Session session = mock(ChatSseService.Session.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        when(request.startAsync(request, response)).thenReturn(async);
        ChatSseRelay relay = new ChatSseRelay(service,
                new ChatSseProperties(Duration.ofSeconds(60), Duration.ofSeconds(240), 1));
        relay.start(request, response, session);
        verify(async).setTimeout(240_000L);
        ArgumentCaptor<AsyncListener> listener = ArgumentCaptor.forClass(AsyncListener.class);
        verify(async).addListener(listener.capture());
        listener.getValue().onTimeout(new AsyncEvent(async));
        listener.getValue().onError(new AsyncEvent(async));
        verify(session, times(2)).close();
        verify(async, times(2)).complete();
        verify(response).flushBuffer();
        verify(response, never()).sendError(anyInt());
    }

    @Test
    @DisplayName("스트림 시작 후 쓰기 오류는 JSON이나 종료 이벤트를 쓰지 않고 연결을 종료한다")
    void start_completesResponseAfterWriteFailure() throws Exception {
        ChatSseService service = mock(ChatSseService.class);
        ChatSseService.Session session = mock(ChatSseService.Session.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        AsyncContext async = mock(AsyncContext.class);
        ServletOutputStream output = mock(ServletOutputStream.class);
        when(request.startAsync(request, response)).thenReturn(async);
        when(response.getOutputStream()).thenReturn(output);
        doThrow(new IOException("frontend disconnected")).when(session).transferTo(output);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(service).execute(eq(session), any(Runnable.class));
        ChatSseRelay relay = new ChatSseRelay(service,
                new ChatSseProperties(Duration.ofSeconds(60), Duration.ofSeconds(240), 1));
        relay.start(request, response, session);
        verify(session).close();
        verify(async).complete();
        verify(response, never()).sendError(anyInt());
        verify(response, never()).getWriter();
    }

    @Test
    @DisplayName("비동기 응답 시작 실패는 열린 upstream을 정리하고 공통 예외 처리로 전달한다")
    void start_closesSessionWhenAsyncSetupFails() {
        ChatSseService service = mock(ChatSseService.class);
        ChatSseService.Session session = mock(ChatSseService.Session.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.startAsync(request, response)).thenThrow(new IllegalStateException("async disabled"));
        ChatSseRelay relay = new ChatSseRelay(service,
                new ChatSseProperties(Duration.ofSeconds(60), Duration.ofSeconds(240), 1));
        assertThatThrownBy(() -> relay.start(request, response, session))
                .isInstanceOf(IllegalStateException.class);
        verify(session).close();
    }
}
