package com.gift.gift.domain.auth.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.gift.gift.domain.auth.support.LoginRateLimitDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginEmailFailureLimitRedisServiceTest {

    private static final String EMAIL_HASH =
            "b".repeat(64);

    private static final String KEY_PREFIX =
            "gift:auth:login:email:";

    private static final int FAILURE_WINDOW_MILLIS =
            5 * 60 * 1_000;

    private static final int MAX_FAILURE_COUNT = 5;
    private static final int MAX_BACKOFF_LEVEL = 6;
    private static final int KEY_TTL_MILLIS =
            24 * 60 * 60 * 1_000;

    @Mock
    private StringRedisTemplate redisTemplate;

    private LoginEmailFailureLimitRedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new LoginEmailFailureLimitRedisService(redisTemplate);
    }

    @Test
    @DisplayName("Redis가 0을 반환하면 이메일 제한 요청을 허용한다")
    void inspect_permitsWhenRedisReturnsZero() {
        givenRedisResult(0L);

        LoginRateLimitDecision decision = redisService.inspect(EMAIL_HASH);

        assertEquals(LoginRateLimitDecision.permit(), decision);

        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of(KEY_PREFIX + EMAIL_HASH)),
                eq("inspect"),
                eq(Integer.toString(FAILURE_WINDOW_MILLIS)),
                eq(Integer.toString(MAX_FAILURE_COUNT)),
                eq(Integer.toString(MAX_BACKOFF_LEVEL)),
                eq(Integer.toString(KEY_TTL_MILLIS))
        );
    }

    @Test
    @DisplayName("Redis가 남은 차단 시간을 반환하면 요청을 제한한다")
    void inspect_rejectsWhenRedisReturnsRetryAfter() {
        givenRedisResult(30L);

        LoginRateLimitDecision decision =
                redisService.inspect(EMAIL_HASH);

        assertEquals(LoginRateLimitDecision.reject(30), decision);
    }

    @Test
    @DisplayName("로그인 실패 기록은 record 작업으로 Redis 스크립트를 실행한다")
    void recordFailure_executesRecordOperation() {
        givenRedisResult(0L);

        LoginRateLimitDecision decision = redisService.recordFailure(EMAIL_HASH);

        assertEquals(LoginRateLimitDecision.permit(), decision);

        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of(KEY_PREFIX + EMAIL_HASH)),
                eq("record"),
                eq(Integer.toString(FAILURE_WINDOW_MILLIS)),
                eq(Integer.toString(MAX_FAILURE_COUNT)),
                eq(Integer.toString(MAX_BACKOFF_LEVEL)),
                eq(Integer.toString(KEY_TTL_MILLIS))
        );
    }

    @Test
    @DisplayName("Redis가 잘못된 null 결과를 반환하면 저장소 응답 오류로 처리한다")
    void inspect_throwsWhenRedisReturnsNull() {
        givenRedisResult(null);

        assertThrows(
                DataRetrievalFailureException.class,
                () -> redisService.inspect(EMAIL_HASH)
        );
    }

    @Test
    @DisplayName("Redis가 음수 결과를 반환하면 저장소 응답 오류로 처리한다")
    void inspect_throwsWhenRedisReturnsNegativeResult() {
        givenRedisResult(-1L);

        assertThrows(
                DataRetrievalFailureException.class,
                () -> redisService.inspect(EMAIL_HASH)
        );
    }

    @Test
    @DisplayName("Redis 접근 오류는 호출자에게 전달한다")
    void inspect_propagatesRedisAccessFailure() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Redis unavailable");

        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenThrow(failure);

        DataAccessResourceFailureException thrown =
                assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> redisService.inspect(EMAIL_HASH)
                );

        assertSame(failure, thrown);
    }

    @Test
    @DisplayName("로그인 성공 초기화는 이메일 해시 키를 삭제한다")
    void reset_deletesHashedEmailKey() {
        redisService.reset(EMAIL_HASH);

        verify(redisTemplate).delete(KEY_PREFIX + EMAIL_HASH);
    }

    private void givenRedisResult(Long result) {
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(result);
    }
}
