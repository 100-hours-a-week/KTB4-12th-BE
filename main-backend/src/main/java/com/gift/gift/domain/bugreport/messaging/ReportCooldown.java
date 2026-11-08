package com.gift.gift.domain.bugreport.messaging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class ReportCooldown {
    private static final DefaultRedisScript<Long> REMAINING = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local due = math.max(tonumber(ARGV[1]), tonumber(redis.call('GET', KEYS[1]) or '0'),
                tonumber(redis.call('GET', KEYS[2]) or '0'))
            return math.max(0, due - now)
            """, Long.class);
    private static final DefaultRedisScript<Long> EXTEND = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = time[1] * 1000 + math.floor(time[2] / 1000)
            local due = math.max(now + tonumber(ARGV[1]), tonumber(redis.call('GET', KEYS[1]) or '0'))
            redis.call('SET', KEYS[1], tostring(due), 'PX', math.ceil(due - now + 60000))
            return due
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String scopeKey;
    private final String globalKey;

    public ReportCooldown(StringRedisTemplate redis, String channelName, String scope, String projectScope) {
        this.redis = redis;
        String prefix = "report:" + channelName + ":cooldown:";
        this.scopeKey = prefix + hash(scope);
        this.globalKey = prefix + ("global".equals(projectScope) ? "global" : "project:" + hash(projectScope));
    }

    private static String hash(String scope) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public long remainingMs(long nextAttemptAt) {
        Long remaining = redis.execute(REMAINING, List.of(scopeKey, globalKey), Long.toString(nextAttemptAt));
        if (remaining == null) throw new IllegalStateException("Cooldown read unavailable");
        return remaining;
    }

    public long extend(long delayMs, boolean global) {
        Long due = redis.execute(EXTEND, List.of(global ? globalKey : scopeKey), Long.toString(delayMs));
        if (due == null) throw new IllegalStateException("Cooldown update unavailable");
        return due;
    }
}
