package com.gift.gift.domain.roulette.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "roulette_ticket_grants", uniqueConstraints = @UniqueConstraint(
        name = "uk_roulette_grants_source", columnNames = "source_key"
))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouletteTicketGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "participation_date", nullable = false, updatable = false)
    private LocalDate participationDate;

    @Column(name = "source_key", nullable = false, updatable = false, length = 100)
    private String sourceKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 20)
    private RouletteTicketSource sourceType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
