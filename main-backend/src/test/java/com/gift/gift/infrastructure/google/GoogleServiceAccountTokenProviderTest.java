package com.gift.gift.infrastructure.google;

import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleServiceAccountTokenProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private BugReportGoogleSheetsProperties settings() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        String privateKey = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        String json = mapper.writeValueAsString(Map.of("client_email", "account@example.test",
            "project_id", "project", "private_key", privateKey));
        return new BugReportGoogleSheetsProperties(Base64.getEncoder().encodeToString(json.getBytes()), "sheet", "tab");
    }

    @Test
    @DisplayName("발급·캐시·quota scope를 같은 계정에서 사용하고 원래 처리 마감 시각을 HTTP 요청으로 전달한다")
    void cachesTokenAndUsesAttemptDeadline() throws Exception {
        var client = mock(GoogleApiClient.class);
        when(client.request(any(), anyLong())).thenReturn(mapper.readTree("{\"access_token\":\"test-token\",\"expires_in\":3600}"));
        var tokens = new GoogleServiceAccountTokenProvider(settings(), mapper, client);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        assertThat(tokens.getAccessToken(deadline)).isEqualTo("test-token");
        assertThat(tokens.getAccessToken(deadline)).isEqualTo("test-token");
        verify(client, times(1)).request(any(), eq(deadline));
        assertThat(tokens.projectScope()).isEqualTo("project");
        assertThat(tokens.accountScope()).isEqualTo("project:account@example.test");
        assertThatThrownBy(() -> tokens.getAccessToken(System.nanoTime() - 1))
            .isInstanceOf(GoogleApiFailure.class).hasMessage("Timeout");
    }

    @Test
    @DisplayName("토큰 갱신 락 대기도 처리 예산을 넘기지 않고 무효 자격증명은 영구 실패로 분류한다")
    void tokenLockWaitIsBoundedAndInvalidCredentialsArePermanent() throws Exception {
        var client = mock(GoogleApiClient.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.request(any(), anyLong())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
            return mapper.readTree("{\"access_token\":\"test-token\",\"expires_in\":3600}");
        });
        var tokens = new GoogleServiceAccountTokenProvider(settings(), mapper, client);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> tokens.getAccessToken(System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> tokens.getAccessToken(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)))
                    .isInstanceOf(GoogleApiFailure.class).hasMessage("Timeout");
            } finally {
                release.countDown();
            }
            assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("test-token");
        }
        var invalid = new GoogleServiceAccountTokenProvider(
            new BugReportGoogleSheetsProperties("not-base64", "sheet", "tab"), mapper, client);
        try {
            invalid.getAccessToken(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            fail("Invalid credentials must fail");
        } catch (GoogleApiFailure failure) {
            assertThat(failure.result().permanent()).isTrue();
        }
    }
}
