package com.gift.gift.domain.gift.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.gift.entity.GiftHistory;

public interface GiftHistoryRepository extends JpaRepository<GiftHistory, Long> {

    Optional<GiftHistory> findBySender_IdAndIdempotencyKey(Long senderId, UUID idempotencyKey);
}
