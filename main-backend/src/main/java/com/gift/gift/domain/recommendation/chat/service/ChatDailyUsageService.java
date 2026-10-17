package com.gift.gift.domain.recommendation.chat.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.recommendation.chat.entity.AiChatTurnUsage;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.repository.AiChatTurnUsageRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;

@Service
@RequiredArgsConstructor
public class ChatDailyUsageService {

    static final int DAILY_LIMIT = 10;

    private final AiChatTurnUsageRepository usageRepository;
    private final UserQueryService userQueryService;
    private final Clock clock;

    @Transactional
    public void consume(
            Long userId,
            UUID conversationId,
            UUID turnId
    ) {
        User user = userQueryService.findActiveUserForUpdate(userId)
                .orElseThrow(() -> new ChatException(
                        ChatErrorCode.RESOURCE_NOT_FOUND
                ));

        if (usageRepository.existsByUser_IdAndConversationIdAndTurnId(
                userId,
                conversationId,
                turnId
        )) {
            return;
        }

        LocalDate usageDate = LocalDate.now(clock);

        if (usageRepository.countByUser_IdAndUsageDate(
                userId,
                usageDate
        ) >= DAILY_LIMIT) {
            throw new ChatException(
                    ChatErrorCode.DAILY_LIMIT_EXCEEDED
            );
        }

        usageRepository.saveAndFlush(new AiChatTurnUsage(
                user,
                conversationId,
                turnId,
                usageDate
        ));
    }
}
