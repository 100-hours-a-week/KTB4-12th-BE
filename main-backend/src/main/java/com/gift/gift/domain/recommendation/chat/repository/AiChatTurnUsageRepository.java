package com.gift.gift.domain.recommendation.chat.repository;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.recommendation.chat.entity.AiChatTurnUsage;

public interface AiChatTurnUsageRepository
        extends JpaRepository<AiChatTurnUsage, Long> {

    boolean existsByUser_IdAndConversationIdAndTurnId(
            Long userId,
            UUID conversationId,
            UUID turnId
    );

    long countByUser_IdAndUsageDate(Long userId, LocalDate usageDate);
}
