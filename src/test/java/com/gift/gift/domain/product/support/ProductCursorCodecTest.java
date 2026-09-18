package com.gift.gift.domain.product.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCursorCodecTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final ProductCursorCodec codec =
            new ProductCursorCodec(jsonMapper);

    private final LocalDateTime createdAt =
            LocalDateTime.of(2026, 9, 17, 12, 0, 0, 123456000);

    @ParameterizedTest
    @EnumSource(ProductSort.class)
    @DisplayName("정렬별 커서를 인코딩하고 디코딩하면 원본이 유지된다")
    void roundTrip(ProductSort sort) {
        ProductSearchCondition condition =
                new ProductSearchCondition(
                        "크림",
                        List.of(11L, 12L),
                        sort
                );

        ProductCursor original = payload(condition);

        String encoded = codec.encode(original, condition);
        ProductCursor decoded = codec.decode(encoded, condition);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("커서가 없으면 첫 페이지로 처리한다")
    void acceptMissingCursor() {
        assertThat(codec.decode(null, condition())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "%%%", "a"})
    @DisplayName("비어 있거나 잘못된 Base64 커서를 거부한다")
    void rejectMalformedBase64(String cursor) {
        assertInvalid(cursor, condition());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "null",
            "{}",
            "{\"sort\":\"UNKNOWN\"}"
    })
    @DisplayName("잘못된 JSON 또는 불완전한 커서를 거부한다")
    void rejectMalformedJson(String json) {
        String cursor = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));

        assertInvalid(cursor, condition());
    }

    @Test
    @DisplayName("커서와 현재 정렬이 다르면 거부한다")
    void rejectDifferentSort() {
        ProductSearchCondition original = condition();

        String cursor = codec.encode(payload(original), original);

        ProductSearchCondition changed = new ProductSearchCondition(
                original.query(),
                original.categoryIds(),
                ProductSort.NEWEST
        );

        assertInvalid(cursor, changed);
    }

    @Test
    @DisplayName("검색어가 바뀌면 기존 커서를 거부한다")
    void rejectDifferentKeyword() {
        ProductSearchCondition original = condition();
        String cursor = codec.encode(payload(original), original);

        ProductSearchCondition changed = new ProductSearchCondition(
                "토너",
                original.categoryIds(),
                original.sort()
        );

        assertInvalid(cursor, changed);
    }

    @Test
    @DisplayName("카테고리가 바뀌면 기존 커서를 거부한다")
    void rejectDifferentCategories() {
        ProductSearchCondition original = condition();
        String cursor = codec.encode(payload(original), original);

        ProductSearchCondition changed = new ProductSearchCondition(
                original.query(),
                List.of(13L),
                original.sort()
        );

        assertInvalid(cursor, changed);
    }

    @Test
    @DisplayName("검색어 공백과 카테고리 순서만 다르면 같은 조건으로 처리한다")
    void acceptEquivalentConditions() {
        ProductSearchCondition original = condition();
        ProductCursor payload = payload(original);
        String cursor = codec.encode(payload, original);

        ProductSearchCondition equivalent = new ProductSearchCondition(
                "  크림  ",
                List.of(12L, 11L, 11L),
                ProductSort.POPULAR
        );

        assertThat(codec.decode(cursor, equivalent)).isEqualTo(payload);
    }

    @Test
    @DisplayName("필수값 누락과 유효하지 않은 커서 값을 거부한다")
    void rejectInvalidPayloadFields() {
        ProductSearchCondition condition = condition();
        ProductCursor valid = payload(condition);

        List<ProductCursor> invalidPayloads = List.of(
                new ProductCursor(
                        2, valid.sort(), valid.query(), valid.categoryIds(),
                        valid.productId(), createdAt, 10, 5
                ),
                new ProductCursor(
                        1, null, valid.query(), valid.categoryIds(),
                        valid.productId(), createdAt, 10, 5
                ),
                new ProductCursor(
                        1, valid.sort(), null, valid.categoryIds(),
                        valid.productId(), createdAt, 10, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), null,
                        valid.productId(), createdAt, 10, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        null, createdAt, 10, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        0L, createdAt, 10, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        valid.productId(), null, 10, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        valid.productId(), createdAt, null, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        valid.productId(), createdAt, 10, null
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        valid.productId(), createdAt, -1, 5
                ),
                new ProductCursor(
                        1, valid.sort(), valid.query(), valid.categoryIds(),
                        valid.productId(), createdAt, 10, -1
                )
        );

        for (ProductCursor invalid : invalidPayloads) {
            // encode()의 사전 검증을 거치지 않고 잘못된 입력을 만든다.
            String cursor = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(jsonMapper.writeValueAsBytes(invalid));

            assertInvalid(cursor, condition);
        }
    }

    private ProductSearchCondition condition() {
        return new ProductSearchCondition(
                "크림",
                List.of(11L, 12L),
                ProductSort.POPULAR
        );
    }

    private ProductCursor payload(ProductSearchCondition condition) {
        boolean newest = condition.sort() == ProductSort.NEWEST;

        return new ProductCursor(
                1,
                condition.sort(),
                condition.query(),
                condition.categoryIds(),
                101L,
                createdAt,
                newest ? null : 10,
                newest ? null : 5
        );
    }

    private void assertInvalid(
            String cursor,
            ProductSearchCondition condition
    ) {
        assertThatThrownBy(() -> codec.decode(cursor, condition))
                .isInstanceOfSatisfying(
                        ProductException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_CURSOR)
                );
    }
}
