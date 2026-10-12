package com.gift.gift.domain.recommendation.chat.service;

import java.time.Instant;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.recommendation.chat.ChatConversationAccessService;
import com.gift.gift.domain.recommendation.chat.ChatConversationIdentity;
import com.gift.gift.domain.recommendation.chat.ChatConversationTokenProvider;
import com.gift.gift.domain.recommendation.chat.ChatModels.Conversation;
import com.gift.gift.domain.recommendation.chat.ChatModels.CreatedConversation;
import com.gift.gift.domain.recommendation.chat.ChatModels.Turn;
import com.gift.gift.domain.recommendation.chat.dto.request.ChatRequests;
import com.gift.gift.domain.recommendation.chat.exception.ChatInputException;
import com.gift.gift.global.exception.ValidationErrorReason;
import com.gift.gift.infrastructure.ai.AiChatClient;
import com.gift.gift.infrastructure.ai.AiChatClient.Result;

@Service
@RequiredArgsConstructor
public class ChatService {
    private final ChatConversationAccessService access;
    private final ChatConversationTokenProvider tokens;
    private final AiChatClient ai;

    public Result<CreatedConversation> create(
            Long userId,
            String id,
            ChatRequests.Create body,
            Instant loginExpiresAt
    ) {
        if (body == null) {
            throw ChatRequests.invalid();
        }

        var identity = access.prepare(userId, id, body.recipientId());
        var result = ai.create(identity);
        var token = tokens.issue(identity, loginExpiresAt);

        return new Result<>(
                result.status(),
                CreatedConversation.from(result.data(), token.value())
        );
    }

    public Conversation conversation(Long userId, String id, String token) {
        var identity = access.authorize(userId, id, token);
        return ai.conversation(identity.conversationId());
    }

    public Result<Turn> submit(
            Long userId,
            String id,
            String token,
            String turnId,
            ChatRequests.Submit body
    ) {
        var identity = access.authorize(userId, id, token);
        UUID turn = ChatConversationIdentity.parseConversationId(turnId);

        if (body == null) {
            throw new ChatInputException(ValidationErrorReason.REQUIRED);
        }

        return ai.submit(
                identity.conversationId(),
                turn,
                body.normalizedMessage()
        );
    }

    public Turn turn(Long userId, String id, String token, String turnId) {
        var identity = access.authorize(userId, id, token);

        return ai.turn(
                identity.conversationId(),
                ChatConversationIdentity.parseConversationId(turnId)
        );
    }

    public Turn cancel(Long userId, String id, String token, String turnId) {
        var identity = access.authorize(userId, id, token);

        return ai.cancel(
                identity.conversationId(),
                ChatConversationIdentity.parseConversationId(turnId)
        );
    }
}
