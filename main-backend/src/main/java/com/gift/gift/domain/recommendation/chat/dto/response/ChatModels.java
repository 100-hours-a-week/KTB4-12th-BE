package com.gift.gift.domain.recommendation.chat.dto.response;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;

public final class ChatModels {
    private ChatModels() {}

    public enum Status {
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record Conversation(UUID conversationId, List<Turn> turns) {
        public void validate(UUID expectedId) {
            require(expectedId.equals(conversationId) && turns != null);

            HashSet<UUID> ids = new HashSet<UUID>();

            for (Turn turn : turns) {
                require(turn != null);
                turn.validate(turn.turnId());
                require(ids.add(turn.turnId()));
            }
        }
    }

    public record CreatedConversation(
            UUID conversationId,
            List<Turn> turns,
            String conversationToken
    ) {
        public static CreatedConversation from(
                Conversation conversation,
                String token
        ) {
            return new CreatedConversation(
                    conversation.conversationId(),
                    conversation.turns(),
                    token
            );
        }

        @Override
        public String toString() {
            return "CreatedConversation[REDACTED]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Turn(
            UUID turnId,
            Status status,
            String message,
            List<Block> blocks,
            Long lastEventId,
            String progressMessage,
            TurnError error
    ) {
        public void validate(UUID expectedId) {
            require(expectedId != null && expectedId.equals(turnId)
                    && turnId.version() == 4 && turnId.variant() == 2);

            require(status != null && message != null && blocks != null
                    && lastEventId != null && lastEventId >= 0);

            require(status == Status.PROCESSING || progressMessage == null);

            require(status == Status.FAILED
                    ? error != null && error.message() != null
                    : error == null);

            int cards = 0;
            HashSet<Long> productIds = new HashSet<Long>();

            for (Block block : blocks) {
                require(block != null);

                if ("text".equals(block.type())) {
                    require(block.text() != null && block.products() == null);
                } else if ("cards".equals(block.type())) {
                    require(++cards <= 1
                            && block.text() == null
                            && block.products() != null
                            && !block.products().isEmpty()
                            && block.products().size() <= 3);

                    for (Product product : block.products()) {
                        require(product != null
                                && product.productId() != null
                                && product.productId() > 0
                                && product.productId() <= ChatConversationIdentity.MAX_USER_ID
                                && product.reason() != null);

                        String reason = product.reason().strip();

                        require(!reason.isBlank()
                                && reason.codePointCount(0, reason.length()) <= 200
                                && productIds.add(product.productId()));
                    }
                } else {
                    throw new IllegalArgumentException("알 수 없는 AI 블록입니다.");
                }
            }
        }

        @Override
        public String toString() {
            return "Turn[REDACTED]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(
            String type,
            String text,
            List<Product> products
    ) {}

    public record Product(Long productId, String reason) {}

    public record TurnError(String message) {}

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("AI 응답 계약이 올바르지 않습니다.");
        }
    }
}
