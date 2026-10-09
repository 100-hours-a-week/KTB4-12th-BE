package com.gift.gift.domain.bugreport.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;

@Component
@RequiredArgsConstructor
public class BugReportRateLimiter {

    private static final int USER_LIMIT_PER_MINUTE = 5;
    private static final int IP_LIMIT_PER_MINUTE = 20;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<Long, Deque<Instant>> userAttempts = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> ipAttempts = new ConcurrentHashMap<>();

    public void checkUser(Long userId) {
        check(userAttempts, userId, USER_LIMIT_PER_MINUTE);
    }

    public void checkIp(String ip) {
        check(ipAttempts, ip, IP_LIMIT_PER_MINUTE);
    }

    private <K> void check(Map<K, Deque<Instant>> store, K key, int limit) {
        Deque<Instant> attempts = store.computeIfAbsent(
                key,
                k -> new ArrayDeque<>()
        );

        synchronized (attempts) {
            Instant windowStart = clock.instant().minus(WINDOW);

            while (!attempts.isEmpty()
                    && attempts.peekFirst().isBefore(windowStart)) {
                attempts.pollFirst();
            }

            if (attempts.size() >= limit) {
                throw new BugReportException(
                        BugReportErrorCode.RATE_LIMIT_EXCEEDED
                );
            }

            attempts.addLast(clock.instant());
        }
    }
}
