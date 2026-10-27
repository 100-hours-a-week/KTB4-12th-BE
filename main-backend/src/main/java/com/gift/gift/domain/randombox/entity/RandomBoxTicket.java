package com.gift.gift.domain.randombox.entity;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "random_box_tickets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RandomBoxTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private Integer campaignId;

    @Column(name = "slot_number", nullable = false, updatable = false)
    private int slotNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prize_id", updatable = false)
    private RandomBoxPrize prize;

    @Column(name = "assigned_user_id")
    private Long assignedUserId;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "opened_at")
    private LocalDateTime openedAt;

    public void assign(Long userId, LocalDateTime acquiredAt) {
        if (assignedUserId != null) {
            throw new IllegalStateException("이미 배정된 랜덤박스입니다.");
        }
        assignedUserId = Objects.requireNonNull(userId, "userId");
        assignedAt = Objects.requireNonNull(acquiredAt, "acquiredAt").truncatedTo(ChronoUnit.MICROS);
    }

    public void open(LocalDateTime now) {
        if (assignedUserId == null) {
            throw new IllegalStateException("배정되지 않은 랜덤박스입니다.");
        }
        if (openedAt == null) {
            // DB DATETIME(6)와 같은 정밀도로 저장해 최초 응답과 재응답 시각을 일치시킨다.
            openedAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
        }
    }

    public RandomBoxStatus status() {
        if (openedAt == null) {
            return RandomBoxStatus.UNOPENED;
        }
        return prize == null ? RandomBoxStatus.MISS : RandomBoxStatus.WON;
    }
}
