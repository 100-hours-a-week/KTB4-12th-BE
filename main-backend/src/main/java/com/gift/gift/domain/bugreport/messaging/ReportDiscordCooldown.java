package com.gift.gift.domain.bugreport.messaging;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class ReportDiscordCooldown {
    private static final String GLOBAL_KEY = "report:discord:cooldown:global";
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
    private final String webhookKey;

    public ReportDiscordCooldown(StringRedisTemplate redis, String webhookUrl) {
        this.redis = redis;
        URI uri = URI.create(webhookUrl);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((uri.getAuthority() + uri.getPath()).getBytes(StandardCharsets.UTF_8));
            this.webhookKey = "report:discord:cooldown:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public long remainingMs(long nextAttemptAt) {
        Long remaining = redis.execute(REMAINING, List.of(webhookKey, GLOBAL_KEY), Long.toString(nextAttemptAt));
        if (remaining == null) throw new IllegalStateException("Cooldown read unavailable");
        return remaining;
    }

    public long extend(long delayMs, boolean global) {
        Long due = redis.execute(EXTEND, List.of(global ? GLOBAL_KEY : webhookKey), Long.toString(delayMs));
        if (due == null) throw new IllegalStateException("Cooldown update unavailable");
        return due;
    }
}
