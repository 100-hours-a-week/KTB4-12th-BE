package com.gift.gift.global.security;

import java.util.List;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.gift.gift.global.exception.ErrorCode;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshCookieOriginFilterTest {

    private static final String ALLOWED_ORIGIN =
            "http://localhost:3000";

    @Mock
    private SecurityErrorResponseWriter responseWriter;

    @Mock
    private FilterChain filterChain;

    private RefreshCookieOriginFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RefreshCookieOriginFilter(
                new CorsProperties(List.of(ALLOWED_ORIGIN)),
                responseWriter
        );
    }

    @Test
    @DisplayName("허용된 Origin의 토큰 재발급 요청은 다음 필터로 전달한다")
    void refresh_allowsConfiguredOrigin() throws Exception {
        MockHttpServletRequest request =
                postRequest("/auth/refresh");
        request.addHeader("Origin", ALLOWED_ORIGIN);
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(responseWriter, never())
                .write(response, ErrorCode.CSRF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Origin이 없는 토큰 재발급 요청은 403 응답을 작성하고 중단한다")
    void refresh_rejectsMissingOrigin() throws Exception {
        MockHttpServletRequest request =
                postRequest("/auth/refresh");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(responseWriter)
                .write(response, ErrorCode.CSRF_VALIDATION_FAILED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("허용되지 않은 Origin의 로그아웃 요청은 403 응답을 작성하고 중단한다")
    void logout_rejectsDisallowedOrigin() throws Exception {
        MockHttpServletRequest request =
                postRequest("/auth/logout");
        request.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(responseWriter)
                .write(response, ErrorCode.CSRF_VALIDATION_FAILED);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("로그인 POST 요청은 Origin 검사 대상이 아니다")
    void login_skipsOriginValidation() throws Exception {
        MockHttpServletRequest request =
                postRequest("/auth/login");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(responseWriter, never())
                .write(response, ErrorCode.CSRF_VALIDATION_FAILED);
    }

    @Test
    @DisplayName("GET 요청은 같은 경로여도 Origin 검사 대상이 아니다")
    void refreshGet_skipsOriginValidation() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/auth/refresh");
        request.setServletPath("/auth/refresh");
        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(responseWriter, never())
                .write(response, ErrorCode.CSRF_VALIDATION_FAILED);
    }

    private MockHttpServletRequest postRequest(String path) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", path);
        request.setServletPath(path);

        return request;
    }
}
