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
import com.gift.gift.domain.auth.support.LoginRateLimitProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginIpRateLimitRedisServiceTest {

    private static final String IDENTIFIER_HASH = "a".repeat(64);

    private static final String KEY_PREFIX = "gift:auth:login:ip:";

    @Mock
    private StringRedisTemplate redisTemplate;

    private LoginIpRateLimitRedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new LoginIpRateLimitRedisService(
                redisTemplate,
                new LoginRateLimitProperties("unused", 6)
        );
    }

    @Test
    @DisplayName("Redis가 0초를 반환하면 요청을 허용하고 HMAC 키로 스크립트를 실행한다")
    void consume_permitsWhenRedisReturnsZero() {
        givenRedisResult(0L);

        LoginRateLimitDecision decision = redisService.consume(IDENTIFIER_HASH);

        assertEquals(LoginRateLimitDecision.permit(), decision);

        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of(KEY_PREFIX + IDENTIFIER_HASH)),
                eq("10"),
                eq("6000"),
                eq("60000")
        );
    }

    @Test
    @DisplayName("Redis가 재시도 대기 시간을 반환하면 요청을 제한한다")
    void consume_rejectsWhenRedisReturnsRetryAfter() {
        givenRedisResult(4L);

        LoginRateLimitDecision decision = redisService.consume(IDENTIFIER_HASH);

        assertEquals(
                LoginRateLimitDecision.reject(4),
                decision
        );
    }

    @Test
    @DisplayName("부하 테스트 보충 간격 1초를 Redis에 밀리초로 전달한다")
    void consume_usesConfiguredRefillInterval() {
        redisService = new LoginIpRateLimitRedisService(
                redisTemplate,
                new LoginRateLimitProperties("unused", 1)
        );
        givenRedisResult(0L);

        redisService.consume(IDENTIFIER_HASH);

        verify(redisTemplate).execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                eq(List.of(KEY_PREFIX + IDENTIFIER_HASH)),
                eq("10"),
                eq("1000"),
                eq("60000")
        );
    }

    @Test
    @DisplayName("Redis 결과가 없으면 저장소 응답 오류로 처리한다")
    void consume_throwsWhenRedisReturnsNull() {
        givenRedisResult(null);

        assertThrows(
                DataRetrievalFailureException.class,
                () -> redisService.consume(IDENTIFIER_HASH)
        );
    }

    @Test
    @DisplayName("Redis 접근 오류는 호출자에게 전달해 MySQL fallback을 선택하게 한다")
    void consume_propagatesRedisAccessFailure() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Redis unavailable");

        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(),
                any(),
                any()
        )).thenThrow(failure);

        DataAccessResourceFailureException thrown =
                assertThrows(
                        DataAccessResourceFailureException.class,
                        () -> redisService.consume(IDENTIFIER_HASH)
                );

        assertSame(failure, thrown);
    }

    private void givenRedisResult(Long result) {
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(),
                any(),
                any(),
                any()
        )).thenReturn(result);
    }
}
