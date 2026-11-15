package com.gift.gift.domain.product.search;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ProductSearchRepositoryTest {

    private ElasticsearchOperations operations;
    private ProductSearchRepository repository;

    @BeforeEach
    void setUp() {
        operations = mock(ElasticsearchOperations.class);
        repository = new ProductSearchRepository(operations, "gift-products-test");
    }

    @ParameterizedTest
    @MethodSource("listingCases")
    @DisplayName("검색어 없는 조회는 텍스트 조건 없이 카테고리·삭제 조건과 정렬을 사용한다")
    void search_buildsListingQuery(List<Long> categories, ProductSort sort) {
        repository.search(new ProductSearchCondition("   ", categories, sort), null, 21);

        NativeQuery query = captureQuery();
        var filters = query.getQuery().bool().filter();
        if (categories.isEmpty()) {
            assertThat(filters).isEmpty();
        } else {
            assertThat(filters).hasSize(1);
            assertThat(filters.getFirst().isTerms()).isTrue();
            assertThat(filters.getFirst().terms().field()).isEqualTo("category_id");
            assertThat(filters.getFirst().terms().terms().value())
                    .extracting(value -> value.stringValue())
                    .containsExactlyElementsOf(categories.stream().map(Object::toString).toList());
        }
        var excluded = query.getQuery().bool().mustNot();
        assertThat(excluded).hasSize(1);
        assertThat(excluded.getFirst().exists().field()).isEqualTo("deleted_at");
        List<String> fields = sort == ProductSort.POPULAR
                ? List.of("views", "sales", "created_at", "id")
                : List.of("sales", "views", "created_at", "id");
        assertThat(query.getSortOptions())
                .extracting(option -> option.field().field())
                .containsExactlyElementsOf(fields);
        assertThat(query.getSortOptions()).allSatisfy(option ->
                assertThat(option.field().order())
                        .isEqualTo(co.elastic.clients.elasticsearch._types.SortOrder.Desc));
        assertThat(query.getSortOptions().get(2).field().format())
                .isEqualTo("strict_date_optional_time_nanos");
        assertThat(query.getPageable().getPageSize()).isEqualTo(21);
        assertThat(query.getTrackTotalHits()).isFalse();
        assertThat(query.getTrackScores()).isFalse();
        assertThat(query.getRequestCache()).isFalse();
    }

    private static Stream<Arguments> listingCases() {
        return Stream.of(ProductSort.POPULAR, ProductSort.MOST_GIFTED)
                .flatMap(sort -> Stream.of(List.<Long>of(), List.of(26L), List.of(26L, 27L, 58L))
                        .map(categories -> Arguments.of(categories, sort)));
    }

    @ParameterizedTest
    @EnumSource(value = ProductSort.class, names = {"POPULAR", "MOST_GIFTED"})
    @DisplayName("검색어 없는 커서는 정렬 순서와 한국 시간 변환을 유지해 search_after에 전달한다")
    void search_passesSearchAfterWithoutKeyword(ProductSort sort) {
        ProductSearchCondition condition = new ProductSearchCondition("", List.of(), sort);
        ProductCursor cursor = new ProductCursor(
                1, sort, "", List.of(), 20L,
                LocalDateTime.of(2026, 7, 13, 12, 0, 0, 123456000), 100, 10
        );

        repository.search(condition, cursor, 21);

        List<Object> expected = sort == ProductSort.POPULAR
                ? List.of(100, 10, "2026-07-13T03:00:00.123456Z", 20L)
                : List.of(10, 100, "2026-07-13T03:00:00.123456Z", 20L);
        assertThat(captureQuery().getSearchAfter()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("두 글자 이상 검색은 기존 상품명·브랜드 구문 검색을 유지한다")
    void search_keepsKeywordPhraseFilters() {
        repository.search(new ProductSearchCondition("세트", List.of(), ProductSort.POPULAR), null, 21);

        var filters = captureQuery().getQuery().bool().filter();
        assertThat(filters).hasSize(1);
        var keyword = filters.getFirst().bool();
        assertThat(keyword.minimumShouldMatch()).isEqualTo("1");
        assertThat(keyword.should()).extracting(clause -> clause.matchPhrase().field())
                .containsExactly("name", "brand");
        assertThat(keyword.should()).allSatisfy(clause ->
                assertThat(clause.matchPhrase().query()).isEqualTo("세트"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"세", "😀"})
    @DisplayName("한 코드포인트 검색어는 ES 요청 전에 거부한다")
    void search_rejectsSingleCodePoint(String keyword) {
        assertThatThrownBy(() -> repository.search(
                new ProductSearchCondition(keyword, List.of(), ProductSort.POPULAR), null, 21
        )).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(operations);
    }

    @Test
    @DisplayName("검색어 없는 최신순은 이번 ES 확장 범위에서 제외한다")
    void search_rejectsNewestWithoutKeyword() {
        assertThatThrownBy(() -> repository.search(
                new ProductSearchCondition("", List.of(), ProductSort.NEWEST), null, 21
        )).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(operations);
    }

    private NativeQuery captureQuery() {
        ArgumentCaptor<NativeQuery> captor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(captor.capture(), eq(ProductSearchDocument.class), any(IndexCoordinates.class));
        return captor.getValue();
    }
}
