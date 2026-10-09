package com.gift.gift.domain.product.cursor;

import java.util.Base64;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.global.exception.ErrorCode;

@Component
public class ProductCursorCodec {

    private static final Base64.Encoder ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER =
            Base64.getUrlDecoder();

    private final JsonMapper jsonMapper;

    public ProductCursorCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String encode(
            ProductCursor payload,
            ProductSearchCondition condition
    ) {
        ProductCursorValidator.validate(payload, condition);

        try {
            byte[] json = jsonMapper.writeValueAsBytes(payload);
            return ENCODER.encodeToString(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "상품 커서 생성에 실패했습니다.",
                    exception
            );
        }
    }

    public ProductCursor decode(
            String cursor,
            ProductSearchCondition condition
    ) {
        if (cursor == null) {
            return null;
        }

        if (cursor.isBlank()) {
            throw invalidCursor();
        }

        ProductCursor payload;

        try {
            byte[] json = DECODER.decode(cursor);

            payload = jsonMapper.readValue(
                    json,
                    ProductCursor.class
            );
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalidCursor();
        }

        ProductCursorValidator.validate(payload, condition);
        return payload;
    }

    private static ProductException invalidCursor() {
        return new ProductException(ErrorCode.INVALID_CURSOR);
    }
}
