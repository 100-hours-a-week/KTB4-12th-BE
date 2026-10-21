package com.gift.gift.domain.recommendation.chat.service;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.recommendation.chat.dto.request.ChatRequests;
import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.Conversation;
import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.CreatedConversation;
import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.Turn;
import com.gift.gift.domain.recommendation.chat.exception.ChatInputException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.domain.recommendation.chat.security.ChatConversationTokenProvider;
import com.gift.gift.domain.recommendation.chat.security.IssuedChatConversationToken;
import com.gift.gift.global.exception.ValidationErrorReason;
import com.gift.gift.infrastructure.ai.chat.AiChatClient;
import com.gift.gift.infrastructure.ai.chat.AiChatClient.Result;

@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatConversationAccessService access;
    private final ChatConversationTokenProvider tokens;
    private final AiChatClient ai;
    private final ChatDailyUsageService dailyUsageService;

    public Result<CreatedConversation> createOrRecoverConversation(
            Long userId,
            String id,
            ChatRequests.Create body,
            Instant loginExpiresAt
    ) {
        if (body == null) {
            throw ChatRequests.invalid();
        }

        ChatConversationIdentity identity =
                access.prepare(userId, id, body.recipientId());

        Result<Conversation> result =
                ai.createOrRecoverConversation(identity);

        IssuedChatConversationToken token =
                tokens.issue(identity, loginExpiresAt);

        return new Result<>(
                result.status(),
                CreatedConversation.from(result.data(), token.value())
        );
    }

    public Conversation getConversation(
            Long userId,
            String id,
            String token
    ) {
        ChatConversationIdentity identity =
                access.authorize(userId, id, token);

        return ai.getConversation(identity.conversationId());
    }

    public Result<Turn> submitTurn(
            Long userId,
            String id,
            String token,
            String turnId,
            ChatRequests.Submit body
    ) {
        ChatConversationIdentity identity =
                access.authorize(userId, id, token);

        UUID turn = ChatConversationIdentity.parseConversationId(turnId);

        if (body == null) {
            throw new ChatInputException(ValidationErrorReason.REQUIRED);
        }

        String message = body.normalizedMessage();

        dailyUsageService.consume(
                userId,
                identity.conversationId(),
                turn
        );

        return ai.submitTurn(
                identity.conversationId(),
                turn,
                message
        );
    }

    public Turn getTurn(
            Long userId,
            String id,
            String token,
            String turnId
    ) {
        ChatConversationIdentity identity =
                access.authorize(userId, id, token);

        return ai.getTurn(
                identity.conversationId(),
                ChatConversationIdentity.parseConversationId(turnId)
        );
    }

    public Turn cancelTurn(
            Long userId,
            String id,
            String token,
            String turnId
    ) {
        ChatConversationIdentity identity =
                access.authorize(userId, id, token);

        return ai.cancelTurn(
                identity.conversationId(),
                ChatConversationIdentity.parseConversationId(turnId)
        );
    }
}
