package com.gift.gift.domain.product.support;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.*;

class ProductRecommendedCursorCodecTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ProductCursorCodec codec = new ProductCursorCodec(mapper);
    private final LocalDateTime time = LocalDateTime.of(2026, 10, 1, 12, 0);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("추천 영역과 인기 영역의 v2 커서 정보를 보존한다")
    void roundTripsBothRegions(boolean recommended) {
        ProductSearchCondition condition = condition(ProductSort.AI_RECOMMENDED, 10L, 1L);
        ProductCursor cursor = payload(condition, recommended, recommended ? 20 : null);
        assertThat(codec.decode(codec.encode(cursor, condition), condition)).isEqualTo(cursor);
    }

    @Test
    @DisplayName("Fallback 커서도 원래 요청 정렬과 수신자 및 분석 버전을 보존한다")
    void preservesFallbackContext() {
        ProductSearchCondition condition = condition(ProductSort.POPULAR, 10L, 0L);
        ProductCursor cursor = payload(condition, false, null);
        assertThat(codec.decode(codec.encode(cursor, condition), condition)).isEqualTo(cursor);
        reject(codec.encode(cursor, condition), condition(ProductSort.POPULAR, 10L, 1L));
    }

    @Test
    @DisplayName("다른 수신자·분석 버전·검색 조건·요청 정렬의 커서 재사용을 거부한다")
    void rejectsChangedContext() {
        ProductSearchCondition original = condition(ProductSort.AI_RECOMMENDED, 10L, 1L);
        String cursor = codec.encode(payload(original, true, 1), original);
        reject(cursor, condition(ProductSort.AI_RECOMMENDED, 11L, 1L));
        reject(cursor, condition(ProductSort.AI_RECOMMENDED, 10L, 2L));
        reject(cursor, new ProductSearchCondition("토너", List.of(11L), ProductSort.AI_RECOMMENDED,
                ProductSort.AI_RECOMMENDED, 10L, 1L));
        reject(cursor, new ProductSearchCondition("크림", List.of(12L), ProductSort.AI_RECOMMENDED,
                ProductSort.AI_RECOMMENDED, 10L, 1L));
        ProductSearchCondition fallback = condition(ProductSort.POPULAR, 10L, 0L);
        String fallbackCursor = codec.encode(payload(fallback, false, null), fallback);
        reject(fallbackCursor, new ProductSearchCondition("크림", List.of(11L), ProductSort.POPULAR,
                ProductSort.POPULAR, 10L, null));
    }

    @Test
    @DisplayName("영역 누락과 추천 순위 누락·범위 오류 및 인기 영역의 순위를 거부한다")
    void rejectsInvalidRegionAndRank() {
        ProductSearchCondition condition = condition(ProductSort.AI_RECOMMENDED, 10L, 1L);
        for (ProductCursor cursor : List.of(payload(condition, null, null),
                payload(condition, true, null), payload(condition, true, 0),
                payload(condition, true, 31), payload(condition, false, 1))) {
            reject(Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(cursor)), condition);
        }
        ProductSearchCondition fallback = condition(ProductSort.POPULAR, 10L, 1L);
        reject(Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(payload(fallback, true, 1))), fallback);
    }

    @Test
    @DisplayName("확장 필드가 없던 기존 v1 JSON은 일반 조회에서 계속 사용할 수 있다")
    void acceptsOriginalV1JsonOnlyForGeneralQuery() {
        String json = """
                {"version":1,"sort":"POPULAR","query":"크림","categoryIds":[11],
                 "productId":101,"createdAt":"2026-10-01T12:00:00","views":10,"sales":5}
                """;
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ProductSearchCondition general = new ProductSearchCondition("크림", List.of(11L), ProductSort.POPULAR);
        assertThat(codec.decode(cursor, general).version()).isEqualTo(1);
        reject(cursor, condition(ProductSort.POPULAR, 10L, 0L));
    }

    @Test
    @DisplayName("수신자를 지정한 명시적 일반 정렬은 분석 버전 없는 v2 커서를 사용한다")
    void supportsRecipientGeneralSort() {
        ProductSearchCondition condition = new ProductSearchCondition("크림", List.of(11L),
                ProductSort.NEWEST, ProductSort.NEWEST, 10L, null);
        ProductCursor cursor = new ProductCursor(2, ProductSort.NEWEST, "크림", List.of(11L),
                101L, time, null, null, ProductSort.NEWEST, 10L, null, false, null);
        assertThat(codec.decode(codec.encode(cursor, condition), condition)).isEqualTo(cursor);
    }

    private ProductSearchCondition condition(ProductSort appliedSort, long recipient, long version) {
        return new ProductSearchCondition("크림", List.of(11L), appliedSort,
                ProductSort.AI_RECOMMENDED, recipient, version);
    }

    private ProductCursor payload(ProductSearchCondition condition, Boolean region, Integer rank) {
        return new ProductCursor(2, condition.sort(), condition.query(), condition.categoryIds(),
                101L, time, 10, 5, condition.requestedSort(), condition.recipientUserId(),
                condition.analyzedSourceVersion(), region, rank);
    }

    private void reject(String cursor, ProductSearchCondition condition) {
        assertThatThrownBy(() -> codec.decode(cursor, condition)).isInstanceOfSatisfying(ProductException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
    }
}
