package com.gift.gift.domain.user.entity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.user.support.LoginRateLimitDecision;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(name = "login_email_failure_limits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginEmailFailureLimit extends BaseTimeEntity {

    private static final Duration FAILURE_WINDOW =
            Duration.ofMinutes(5);

    private static final int MAX_FAILURE_COUNT = 5;
    private static final int MAX_BACKOFF_LEVEL = 6;

    @Id
    @NotBlank
    @Size(min = 64, max = 64)
    @Column(
            name = "identifier_hash",
            nullable = false,
            updatable = false,
            length = 64
    )
    private String identifierHash;

    @Min(0)
    @Max(MAX_FAILURE_COUNT)
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @NotNull
    @Column(name = "window_started_at", nullable = false)
    private LocalDateTime windowStartedAt;

    @Min(0)
    @Max(MAX_BACKOFF_LEVEL)
    @Column(name = "backoff_level", nullable = false)
    private int backoffLevel;

    @Column(name = "blocked_until")
    private LocalDateTime blockedUntil;

    public LoginEmailFailureLimit(
            String identifierHash,
            LocalDateTime now
    ) {
        this.identifierHash = Objects.requireNonNull(
                identifierHash,
                "identifierHash must not be null"
        );
        this.windowStartedAt = Objects.requireNonNull(
                now,
                "now must not be null"
        );
        this.failureCount = 0;
        this.backoffLevel = 0;
        this.blockedUntil = null;
    }

    public LoginRateLimitDecision inspect(
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "now must not be null");

        if (isBlocked(now)) {
            return LoginRateLimitDecision.reject(
                    calculateRetryAfterSeconds(now)
            );
        }

        resetExpiredState(now);

        return LoginRateLimitDecision.permit();
    }

    public LoginRateLimitDecision recordFailure(
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "now must not be null");

        if (isBlocked(now)) {
            return LoginRateLimitDecision.reject(
                    calculateRetryAfterSeconds(now)
            );
        }

        resetExpiredState(now);

        failureCount++;

        if (failureCount == MAX_FAILURE_COUNT) {
            activateNextBackoff(now);
        }

        return LoginRateLimitDecision.permit();
    }

    private boolean isBlocked(LocalDateTime now) {
        return blockedUntil != null
                && now.isBefore(blockedUntil);
    }

    private void resetExpiredState(LocalDateTime now) {
        if (blockedUntil != null
                && !now.isBefore(blockedUntil)) {
            blockedUntil = null;
            failureCount = 0;
            windowStartedAt = now;
            return;
        }

        if (!now.isBefore(
                windowStartedAt.plus(FAILURE_WINDOW)
        )) {
            failureCount = 0;
            windowStartedAt = now;
        }
    }

    private void activateNextBackoff(LocalDateTime now) {
        backoffLevel = Math.min(
                backoffLevel + 1,
                MAX_BACKOFF_LEVEL
        );

        blockedUntil = now.plusSeconds(
                backoffSeconds(backoffLevel)
        );

        failureCount = 0;
        windowStartedAt = now;
    }

    private long calculateRetryAfterSeconds(
            LocalDateTime now
    ) {
        long remainingMillis = Duration.between(
                now,
                blockedUntil
        ).toMillis();

        return Math.max(
                1,
                (remainingMillis + 999) / 1000
        );
    }

    private long backoffSeconds(int level) {
        return switch (level) {
            case 1 -> 30;
            case 2 -> 60;
            case 3 -> 120;
            case 4 -> 240;
            case 5 -> 480;
            default -> 900;
        };
    }
}
