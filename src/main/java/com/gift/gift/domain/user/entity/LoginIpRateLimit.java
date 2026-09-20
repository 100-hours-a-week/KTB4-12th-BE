package com.gift.gift.domain.user.entity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
@Table(name = "login_ip_rate_limits")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginIpRateLimit extends BaseTimeEntity {

    private static final BigDecimal CAPACITY =
            BigDecimal.valueOf(10);

    private static final BigDecimal ONE_TOKEN =
            BigDecimal.ONE;

    private static final long REFILL_SECONDS = 6;

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

    @NotNull
    @DecimalMin("0.000")
    @DecimalMax("10.000")
    @Column(
            name = "available_tokens",
            nullable = false,
            precision = 5,
            scale = 3
    )
    private BigDecimal availableTokens;

    @NotNull
    @Column(name = "last_refilled_at", nullable = false)
    private LocalDateTime lastRefilledAt;

    public LoginIpRateLimit(
            String identifierHash,
            LocalDateTime now
    ) {
        this.identifierHash = Objects.requireNonNull(
                identifierHash,
                "identifierHash must not be null"
        );
        this.availableTokens = CAPACITY;
        this.lastRefilledAt = Objects.requireNonNull(
                now,
                "now must not be null"
        );
    }

    public LoginRateLimitDecision consume(
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "now must not be null");

        refill(now);

        if (availableTokens.compareTo(ONE_TOKEN) >= 0) {
            availableTokens = availableTokens.subtract(
                    ONE_TOKEN
            );

            return LoginRateLimitDecision.permit();
        }

        return LoginRateLimitDecision.reject(
                calculateRetryAfterSeconds(now)
        );
    }

    private void refill(LocalDateTime now) {
        long elapsedSeconds = elapsedSeconds(now);
        long refillCount = elapsedSeconds / REFILL_SECONDS;

        if (refillCount == 0) {
            return;
        }

        availableTokens = availableTokens
                .add(BigDecimal.valueOf(refillCount))
                .min(CAPACITY);

        if (availableTokens.compareTo(CAPACITY) == 0) {
            lastRefilledAt = now;
            return;
        }

        lastRefilledAt = lastRefilledAt.plusSeconds(
                refillCount * REFILL_SECONDS
        );
    }

    private long calculateRetryAfterSeconds(
            LocalDateTime now
    ) {
        long elapsedSeconds = elapsedSeconds(now);

        return Math.max(
                1,
                REFILL_SECONDS - elapsedSeconds
        );
    }

    private long elapsedSeconds(LocalDateTime now) {
        if (now.isBefore(lastRefilledAt)) {
            return 0;
        }

        return Duration.between(
                lastRefilledAt,
                now
        ).getSeconds();
    }
}