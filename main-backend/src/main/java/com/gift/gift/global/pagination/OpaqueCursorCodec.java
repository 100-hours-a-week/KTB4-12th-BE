package com.gift.gift.global.pagination;

import java.util.Base64;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpaqueCursorCodec {

    private final ObjectMapper objectMapper;

    public OpaqueCursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(Object cursor) {
        if (cursor == null) {
            throw new IllegalArgumentException("인코딩할 커서는 필수입니다.");
        }

        try {
            byte[] json = objectMapper.writeValueAsBytes(cursor);

            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("커서를 생성할 수 없습니다.", exception);
        }
    }

    public <T> T decode(String rawCursor, Class<T> cursorType) {
        if (rawCursor == null || rawCursor.isBlank() || cursorType == null) {
            throw new InvalidCursorException();
        }

        try {
            byte[] json = Base64.getUrlDecoder().decode(rawCursor);

            return objectMapper.readerFor(cursorType)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(json);
        } catch (IllegalArgumentException | JacksonException exception) {
            throw new InvalidCursorException(exception);
        }
    }
}
