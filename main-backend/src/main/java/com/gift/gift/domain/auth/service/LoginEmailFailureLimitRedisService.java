package com.gift.gift.domain.auth.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.auth.support.LoginRateLimitDecision;

@Service
@RequiredArgsConstructor
public class LoginEmailFailureLimitRedisService {

    private static final String KEY_PREFIX = "gift:auth:login:email:";

    private static final int FAILURE_WINDOW_MILLIS = 5 * 60 * 1_000;

    private static final int MAX_FAILURE_COUNT = 5;
    private static final int MAX_BACKOFF_LEVEL = 6;

    private static final int KEY_TTL_MILLIS = 24 * 60 * 60 * 1_000;

    private static final DefaultRedisScript<Long> PROCESS_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local time = redis.call('TIME')
                    local nowMillis =
                      tonumber(time[1]) * 1000
                      + math.floor(tonumber(time[2]) / 1000)

                    local operation = ARGV[1]
                    local failureWindowMillis = tonumber(ARGV[2])
                    local maxFailureCount = tonumber(ARGV[3])
                    local maxBackoffLevel = tonumber(ARGV[4])
                    local keyTtlMillis = tonumber(ARGV[5])

                    if operation ~= 'inspect'
                      and operation ~= 'record' then
                      return redis.error_reply(
                        'Invalid email rate limit operation'
                      )
                    end

                    local state = redis.call(
                      'HMGET',
                      KEYS[1],
                      'failureCount',
                      'windowStartedAtMillis',
                      'backoffLevel',
                      'blockedUntilMillis'
                    )

                    local storedFailureCount = state[1]
                    local storedWindowStartedAt = state[2]
                    local storedBackoffLevel = state[3]
                    local storedBlockedUntil = state[4]

                    local failureCount
                    local windowStartedAt
                    local backoffLevel
                    local blockedUntil

                    if not storedFailureCount
                      and not storedWindowStartedAt
                      and not storedBackoffLevel
                      and not storedBlockedUntil then
                      if operation == 'inspect' then
                        return 0
                      end

                      failureCount = 0
                      windowStartedAt = nowMillis
                      backoffLevel = 0
                      blockedUntil = 0
                    else
                      if not storedFailureCount
                        or not storedWindowStartedAt
                        or not storedBackoffLevel
                        or not storedBlockedUntil then
                        return redis.error_reply(
                          'Incomplete email rate limit state'
                        )
                      end

                      failureCount = tonumber(storedFailureCount)
                      windowStartedAt =
                        tonumber(storedWindowStartedAt)
                      backoffLevel =
                        tonumber(storedBackoffLevel)
                      blockedUntil =
                        tonumber(storedBlockedUntil)

                      if not failureCount
                        or not windowStartedAt
                        or not backoffLevel
                        or not blockedUntil
                        or failureCount < 0
                        or failureCount > maxFailureCount
                        or failureCount ~= math.floor(failureCount)
                        or backoffLevel < 0
                        or backoffLevel > maxBackoffLevel
                        or backoffLevel ~= math.floor(backoffLevel)
                        or blockedUntil < 0 then
                        return redis.error_reply(
                          'Invalid email rate limit state'
                        )
                      end
                    end

                    if blockedUntil > nowMillis then
                      return math.max(
                        1,
                        math.ceil(
                          (blockedUntil - nowMillis) / 1000
                        )
                      )
                    end

                    local changed = false

                    if blockedUntil > 0 then
                      blockedUntil = 0
                      failureCount = 0
                      windowStartedAt = nowMillis
                      changed = true
                    elseif nowMillis
                      >= windowStartedAt + failureWindowMillis then
                      failureCount = 0
                      windowStartedAt = nowMillis
                      changed = true
                    end

                    if operation == 'record' then
                      failureCount = failureCount + 1
                      changed = true

                      if failureCount == maxFailureCount then
                        local backoffSecondsByLevel = {
                          30,
                          60,
                          120,
                          240,
                          480,
                          900
                        }

                        backoffLevel = math.min(
                          backoffLevel + 1,
                          maxBackoffLevel
                        )

                        blockedUntil = nowMillis
                          + backoffSecondsByLevel[backoffLevel]
                          * 1000

                        failureCount = 0
                        windowStartedAt = nowMillis
                      end
                    end

                    if changed then
                      redis.call(
                        'HSET',
                        KEYS[1],
                        'failureCount',
                        tostring(failureCount),
                        'windowStartedAtMillis',
                        tostring(windowStartedAt),
                        'backoffLevel',
                        tostring(backoffLevel),
                        'blockedUntilMillis',
                        tostring(blockedUntil)
                      )

                      redis.call(
                        'PEXPIRE',
                        KEYS[1],
                        keyTtlMillis
                      )
                    end

                    return 0
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimitDecision inspect(String identifierHash) {
        return execute(identifierHash, "inspect");
    }

    public LoginRateLimitDecision recordFailure(String identifierHash) {
        return execute(identifierHash, "record");
    }

    public void reset(String identifierHash) {
        redisTemplate.delete(KEY_PREFIX + identifierHash);
    }

    private LoginRateLimitDecision execute(String identifierHash, String operation) {

        Long retryAfterSeconds = redisTemplate.execute(
                PROCESS_SCRIPT,
                List.of(KEY_PREFIX + identifierHash),
                operation,
                FAILURE_WINDOW_MILLIS,
                MAX_FAILURE_COUNT,
                MAX_BACKOFF_LEVEL,
                KEY_TTL_MILLIS
        );

        if (retryAfterSeconds == null || retryAfterSeconds < 0) {
            throw new DataRetrievalFailureException("Redis 이메일 로그인 제한 결과가 올바르지 않습니다.");
        }

        if (retryAfterSeconds == 0) {
            return LoginRateLimitDecision.permit();
        }

        return LoginRateLimitDecision.reject(retryAfterSeconds);
    }
}
