package com.gift.gift.domain.roulette.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "roulette_days", uniqueConstraints = @UniqueConstraint(
        name = "uk_roulette_days_user_date", columnNames = {"user_id", "participation_date"}
))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouletteParticipationDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "participation_date", nullable = false, updatable = false)
    private LocalDate participationDate;

    @Column(name = "attendance_granted", nullable = false)
    private int attendanceGranted;

    @Column(name = "gift_granted", nullable = false)
    private int giftGranted;

    @Column(nullable = false)
    private int used;

    public void grantAttendance() {
        if (attendanceGranted != 0) {
            throw new IllegalStateException("이미 출석 참여권을 지급했습니다.");
        }
        attendanceGranted = 1;
    }

    public void grantGift() {
        if (giftGranted == Integer.MAX_VALUE) {
            throw new IllegalStateException("선물 참여권 지급 횟수 한도를 초과했습니다.");
        }
        giftGranted++;
    }

    public void consumeTicket() {
        if (used != 0) {
            throw new IllegalStateException("이미 오늘의 룰렛 참여권을 사용했습니다.");
        }
        used = 1;
    }
}
