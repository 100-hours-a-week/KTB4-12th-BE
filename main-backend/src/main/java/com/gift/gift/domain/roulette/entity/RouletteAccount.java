package com.gift.gift.domain.roulette.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "roulette_accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouletteAccount {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "available_tickets", nullable = false)
    private long availableTickets;

    public void grantTicket() {
        if (availableTickets == Long.MAX_VALUE) {
            throw new IllegalStateException("룰렛 참여권 잔액 한도를 초과했습니다.");
        }
        availableTickets++;
    }

    public void consumeTicket() {
        if (availableTickets <= 0) {
            throw new IllegalStateException("차감할 룰렛 참여권이 없습니다.");
        }
        availableTickets--;
    }
}
