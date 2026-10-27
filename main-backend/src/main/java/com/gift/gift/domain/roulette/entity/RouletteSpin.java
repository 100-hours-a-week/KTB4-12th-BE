package com.gift.gift.domain.roulette.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "roulette_spins", uniqueConstraints = @UniqueConstraint(
        name = "uk_roulette_spins_user_key", columnNames = {"user_id", "idempotency_key"}
))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouletteSpin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, columnDefinition = "BINARY(16)")
    private UUID idempotencyKey;

    @Column(name = "participation_date", nullable = false, updatable = false)
    private LocalDate participationDate;

    @Column(name = "segment_id", nullable = false, updatable = false)
    private int segmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false, updatable = false, length = 20)
    private RouletteRewardType rewardType;

    @Column(updatable = false)
    private Integer multiplier;

    @Column(name = "reward_version", nullable = false, updatable = false, length = 40)
    private String rewardVersion;

    @Column(name = "remaining_tickets", nullable = false, updatable = false)
    private long remainingTickets;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RouletteSpin(Long userId, UUID idempotencyKey, LocalDate participationDate, int segmentId,
            RouletteRewardType rewardType, Integer multiplier, String rewardVersion,
            long remainingTickets, LocalDateTime createdAt) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.participationDate = Objects.requireNonNull(participationDate, "participationDate");
        this.segmentId = segmentId;
        this.rewardType = Objects.requireNonNull(rewardType, "rewardType");
        this.multiplier = multiplier;
        this.rewardVersion = Objects.requireNonNull(rewardVersion, "rewardVersion");
        this.remainingTickets = remainingTickets;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
