package com.gift.gift.domain.recommendation.chat.support;

import java.util.List;

import org.springframework.util.MultiValueMap;

import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;

public final class ChatEventCursor {

    private ChatEventCursor() {}

    public static long parse(
            MultiValueMap<String, String> query
    ) {
        boolean hasUnknownQuery = query.keySet()
                .stream()
                .anyMatch(key -> !"afterEventId".equals(key));

        if (hasUnknownQuery) {
            throw new ChatException(
                    ChatErrorCode.INVALID_REQUEST
            );
        }

        List<String> values = query.get("afterEventId");

        if (values == null) {
            return 0;
        }

        if (values.size() != 1) {
            throw invalidCursor();
        }

        String value = values.getFirst();

        if (value == null || !value.matches("[0-9]{1,19}")) {
            throw invalidCursor();
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalidCursor();
        }
    }

    private static ChatException invalidCursor() {
        return new ChatException(
                ChatErrorCode.INVALID_EVENT_CURSOR
        );
    }
}
