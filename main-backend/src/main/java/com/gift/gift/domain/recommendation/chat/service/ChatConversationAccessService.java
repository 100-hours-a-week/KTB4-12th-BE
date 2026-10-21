package com.gift.gift.domain.recommendation.chat.service;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.domain.recommendation.chat.security.ChatConversationTokenProvider;
import com.gift.gift.domain.user.service.AuthenticatedUserService;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class ChatConversationAccessService {

    private final AuthenticatedUserService authenticatedUserService;
    private final ChatConversationTokenProvider tokenProvider;

    public ChatConversationIdentity prepare(
            Long currentUserId,
            String conversationId,
            Long recipientUserId
    ) {
        long senderId = requireActiveSender(currentUserId);

        UUID id = ChatConversationIdentity.parseConversationId(
                conversationId
        );

        long recipientId = ChatConversationIdentity.requireUserId(
                recipientUserId
        );

        if (!authenticatedUserService.isActive(recipientId)) {
            throw new ChatException(ChatErrorCode.RECIPIENT_NOT_FOUND);
        }

        return new ChatConversationIdentity(
                id,
                senderId,
                recipientId
        );
    }

    public ChatConversationIdentity authorize(
            Long currentUserId,
            String conversationId,
            String conversationToken
    ) {
        long senderId = requireActiveSender(currentUserId);

        UUID id = ChatConversationIdentity.parseConversationId(
                conversationId
        );

        ChatConversationIdentity identity = tokenProvider.verify(
                conversationToken,
                senderId,
                id
        );

        if (!authenticatedUserService.isActive(
                identity.recipientUserId()
        )) {
            throw new ChatException(ChatErrorCode.RESOURCE_NOT_FOUND);
        }

        return identity;
    }

    private long requireActiveSender(Long currentUserId) {
        if (currentUserId == null
                || currentUserId < 1
                || currentUserId > ChatConversationIdentity.MAX_USER_ID
                || !authenticatedUserService.isActive(currentUserId)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        return currentUserId;
    }
}
