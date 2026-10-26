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
public class LoginIpRateLimitRedisService {

    private static final String KEY_PREFIX = "gift:auth:login:ip:";

    private static final int CAPACITY = 10;
    private static final int REFILL_INTERVAL_MILLIS = 6_000;
    private static final int KEY_TTL_MILLIS = 60_000;

    private static final DefaultRedisScript<Long> CONSUME_TOKEN_SCRIPT =
            new DefaultRedisScript<>("""
                    local time = redis.call('TIME')
                    local nowMillis =
                      tonumber(time[1]) * 1000
                      + math.floor(tonumber(time[2]) / 1000)

                    local capacity = tonumber(ARGV[1])
                    local refillIntervalMillis = tonumber(ARGV[2])
                    local keyTtlMillis = tonumber(ARGV[3])

                    local storedState = redis.call(
                      'HMGET',
                      KEYS[1],
                      'tokens',
                      'lastRefilledAt'
                    )

                    local storedTokens = storedState[1]
                    local storedLastRefilledAt = storedState[2]
                    local tokens = tonumber(storedTokens)
                    local lastRefilledAt =
                      tonumber(storedLastRefilledAt)

                    if not storedTokens and not storedLastRefilledAt then
                      tokens = capacity
                      lastRefilledAt = nowMillis
                    elseif not storedTokens
                      or not storedLastRefilledAt
                      or not tokens
                      or not lastRefilledAt
                      or tokens < 0
                      or tokens > capacity
                      or tokens ~= math.floor(tokens) then
                      return redis.error_reply(
                        'Invalid IP rate limit state'
                      )
                    end

                    if nowMillis > lastRefilledAt then
                      local elapsedMillis =
                        nowMillis - lastRefilledAt
                      local refillCount = math.floor(
                        elapsedMillis / refillIntervalMillis
                      )

                      if refillCount > 0 then
                        tokens = math.min(
                          capacity,
                          tokens + refillCount
                        )

                        if tokens == capacity then
                          lastRefilledAt = nowMillis
                        else
                          lastRefilledAt =
                            lastRefilledAt
                            + refillCount * refillIntervalMillis
                        end
                      end
                    end

                    local retryAfterSeconds = 0

                    if tokens >= 1 then
                      tokens = tokens - 1
                    else
                      local elapsedMillis = math.max(
                        0,
                        nowMillis - lastRefilledAt
                      )
                      retryAfterSeconds = math.max(
                        1,
                        math.ceil(
                          (
                            refillIntervalMillis
                            - elapsedMillis
                          ) / 1000
                        )
                      )
                    end

                    redis.call(
                      'HSET',
                      KEYS[1],
                      'tokens',
                      tostring(tokens),
                      'lastRefilledAt',
                      tostring(lastRefilledAt)
                    )
                    redis.call(
                      'PEXPIRE',
                      KEYS[1],
                      keyTtlMillis
                    )

                    return retryAfterSeconds
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public LoginRateLimitDecision consume(
            String identifierHash
    ) {
        Long retryAfterSeconds = redisTemplate.execute(
                CONSUME_TOKEN_SCRIPT,
                List.of(KEY_PREFIX + identifierHash),
                Integer.toString(CAPACITY),
                Integer.toString(REFILL_INTERVAL_MILLIS),
                Integer.toString(KEY_TTL_MILLIS)
        );

        if (retryAfterSeconds == null || retryAfterSeconds < 0) {
            throw new DataRetrievalFailureException(
                    "Redis IP 요청 제한 결과가 올바르지 않습니다."
            );
        }

        if (retryAfterSeconds == 0) {
            return LoginRateLimitDecision.permit();
        }

        return LoginRateLimitDecision.reject(retryAfterSeconds);
    }
}
