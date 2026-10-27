package com.gift.gift.domain.roulette.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.roulette.entity.RouletteSpin;

public interface RouletteSpinRepository extends JpaRepository<RouletteSpin, Long> {

    Optional<RouletteSpin> findByUserIdAndIdempotencyKey(Long userId, UUID idempotencyKey);
}
