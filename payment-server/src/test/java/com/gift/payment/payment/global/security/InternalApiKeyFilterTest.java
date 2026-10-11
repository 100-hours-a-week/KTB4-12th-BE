package com.gift.payment.global.security;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyFilterTest {

    private static final String API_KEY = "test-payment-server-api-key";

    private InternalApiKeyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InternalApiKeyFilter(
                new InternalApiKeyProperties(API_KEY),
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("API Key가 없으면 공통 형식의 401을 반환한다")
    void rejectsMissingKey() throws Exception {
        FilterResult result = execute("/payments/confirm");

        assertUnauthorized(result);
    }

    @Test
    @DisplayName("API Key가 일치하지 않으면 공통 형식의 401을 반환한다")
    void rejectsWrongKey() throws Exception {
        FilterResult result = execute(
                "/payments/confirm",
                "wrong-api-key"
        );

        assertUnauthorized(result);
    }

    @Test
    @DisplayName("API Key 헤더가 여러 개면 요청을 거부한다")
    void rejectsMultipleKeys() throws Exception {
        FilterResult result = execute(
                "/payments/confirm",
                API_KEY,
                API_KEY
        );

        assertUnauthorized(result);
    }

    private FilterResult execute(String path, String... apiKeys) throws Exception {

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI(path);

        for (String apiKey : apiKeys) {
            request.addHeader(InternalApiKeyFilter.API_KEY_HEADER, apiKey);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        chainCalled.set(true)
        );

        return new FilterResult(response, chainCalled.get());
    }

    private void assertUnauthorized(FilterResult result) throws Exception {
        String body = result.response().getContentAsString();

        assertThat(result.chainCalled()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(result.response().getContentType()).startsWith("application/json");
        assertThat(body).contains("\"code\":\"INVALID_INTERNAL_API_KEY\"");
        assertThat(body).contains("\"traceId\":");
        assertThat(body).doesNotContain("\"data\"");
        assertThat(body).doesNotContain("\"details\"");
    }

    private record FilterResult(MockHttpServletResponse response, boolean chainCalled) {
    }
}
