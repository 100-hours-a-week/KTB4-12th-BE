package com.gift.gift.domain.recommendation.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;

import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.exception.ChatInputException;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.global.exception.ValidationErrorReason;

public final class ChatRequests {
    private ChatRequests() {}

    public record Create(JsonNode recipientUserId) {
        public long recipientId() {
            if (recipientUserId == null || !recipientUserId.isIntegralNumber()
                    || !recipientUserId.canConvertToLong()) {
                throw invalid();
            }
            return ChatConversationIdentity.requireUserId(recipientUserId.longValue());
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("허용되지 않은 요청 필드입니다.");
        }

        @Override
        public String toString() {
            return "Create[REDACTED]";
        }
    }

    public record Submit(JsonNode message) {
        public String normalizedMessage() {
            if (message == null || message.isNull()) {
                throw new ChatInputException(ValidationErrorReason.REQUIRED);
            }
            if (!message.isString()) {
                throw invalid();
            }

            String value = message.stringValue().strip();

            if (value.isBlank()) {
                throw new ChatInputException(ValidationErrorReason.REQUIRED);
            }
            if (value.codePointCount(0, value.length()) > 100) {
                throw new ChatInputException(ValidationErrorReason.TOO_LONG);
            }

            // JSON에는 고립 surrogate도 들어올 수 있으므로 유효한 Unicode만 전송한다.
            if (value.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) {
                throw invalid();
            }

            return value;
        }

        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("허용되지 않은 요청 필드입니다.");
        }

        @Override
        public String toString() {
            return "Submit[REDACTED]";
        }
    }

    public static ChatException invalid() {
        return new ChatException(ChatErrorCode.INVALID_REQUEST);
    }
}
