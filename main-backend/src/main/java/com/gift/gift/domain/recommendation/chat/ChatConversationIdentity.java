package com.gift.gift.domain.recommendation.chat;

import java.util.UUID;
import java.util.regex.Pattern;

import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

public record ChatConversationIdentity(
        UUID conversationId,
        long senderUserId,
        long recipientUserId
) {

    public static final long MAX_USER_ID = 9_007_199_254_740_991L;

    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}"
                    + "-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    );

    public ChatConversationIdentity {
        if (conversationId == null
                || conversationId.version() != 4
                || conversationId.variant() != 2) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        requireUserId(senderUserId);
        requireUserId(recipientUserId);
    }

    public static UUID parseConversationId(String value) {
        if (value == null || !UUID_V4.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return UUID.fromString(value);
    }

    public static long requireUserId(Long value) {
        if (value == null || value < 1 || value > MAX_USER_ID) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        return value;
    }
}
