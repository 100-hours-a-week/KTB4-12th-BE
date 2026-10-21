package com.gift.gift.domain.product.search;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.SearchHits;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.product-search.enabled=true",
        "spring.elasticsearch.uris=http://127.0.0.1:9201",
        "app.product-search.index-name=gift-products-local-v1"
})
@EnabledIfEnvironmentVariable(
        named = "RUN_LOCAL_ES_TEST",
        matches = "true"
)
class ProductSearchRepositoryIntegrationTest {

    @Autowired
    private ProductSearchRepository repository;

    @Test
    @DisplayName("부분 검색 결과를 조회수와 구매수 내림차순으로 반환한다")
    void searchFirstPage_returnsMatchingProductsInPopularOrder() {
        ProductSearchCondition condition = new ProductSearchCondition(
                "텀블",
                List.of(),
                ProductSort.POPULAR
        );

        List<ProductSearchDocument> products = repository.searchFirstPage(condition)
                .getSearchHits().stream()
                .map(hit -> hit.getContent())
                .toList();

        assertThat(products)
                .isNotEmpty()
                .hasSizeLessThanOrEqualTo(21);

        assertThat(products).allSatisfy(product -> {
            assertThat(product.id()).isPositive();
            assertThat(product.price()).isNotNull();
            assertThat(product.createdAt()).isNotBlank();

            assertThat(
                    product.name().contains("텀블")
                            || product.brand().contains("텀블")
            ).isTrue();
        });

        Comparator<ProductSearchDocument> order = Comparator
                .comparing(
                        ProductSearchDocument::views,
                        Comparator.reverseOrder()
                )
                .thenComparing(
                        ProductSearchDocument::sales,
                        Comparator.reverseOrder()
                );

        assertThat(products).isSortedAccordingTo(order);
    }

    @Test
    @DisplayName("일치하는 상품이 없으면 빈 결과를 반환한다")
    void searchFirstPage_returnsEmpty_whenNoProductsMatch() {
        ProductSearchCondition condition = new ProductSearchCondition(
                "존재하지않는상품검색검증8f21b7",
                List.of(),
                ProductSort.POPULAR
        );

        SearchHits<ProductSearchDocument> hits = repository.searchFirstPage(condition);

        assertThat(hits.getSearchHits()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ProductSort.class,
            names = {"POPULAR", "MOST_GIFTED", "NEWEST"}
    )
    @DisplayName("20번째 상품의 커서 다음에 기존 21번째 상품이 반환된다")
    void search_returnsNextPageAfterLastDisplayedProduct(
            ProductSort sort
    ) {
        ProductSearchCondition condition = new ProductSearchCondition(
                "세트",
                List.of(),
                sort
        );

        List<ProductSearchDocument> first = repository.search(condition, null, 21)
                .getSearchHits().stream()
                .map(hit -> hit.getContent())
                .toList();

        assertThat(first).hasSize(21);

        // 다음 페이지 확인용 21번째가 아닌 실제 표시한 20번째로 커서를 만든다.
        ProductSearchDocument boundary = first.get(19);
        boolean newest = sort == ProductSort.NEWEST;

        LocalDateTime createdAt = OffsetDateTime.parse(boundary.createdAt())
                .withOffsetSameInstant(ZoneOffset.ofHours(9))
                .toLocalDateTime();

        ProductCursor cursor = new ProductCursor(
                1,
                sort,
                condition.query(),
                condition.categoryIds(),
                boundary.id(),
                createdAt,
                newest ? null : boundary.views(),
                newest ? null : boundary.sales()
        );

        List<ProductSearchDocument> second = repository.search(condition, cursor, 21)
                .getSearchHits().stream()
                .map(hit -> hit.getContent())
                .toList();

        assertThat(second).isNotEmpty();
        assertThat(second.getFirst().id())
                .isEqualTo(first.get(20).id());

        List<Long> displayedIds = first.subList(0, 20).stream()
                .map(ProductSearchDocument::id)
                .toList();

        assertThat(second.stream()
                .map(ProductSearchDocument::id)
                .toList())
                .doesNotContainAnyElementsOf(displayedIds);
    }

    @Test
    @DisplayName("검색 조건과 다른 커서는 거부한다")
    void search_rejectsCursorWithDifferentCondition() {
        ProductSearchCondition condition = new ProductSearchCondition(
                "세트",
                List.of(),
                ProductSort.POPULAR
        );

        ProductCursor cursor = new ProductCursor(
                1,
                ProductSort.POPULAR,
                "텀블",
                List.of(),
                1L,
                java.time.LocalDateTime.of(2026, 1, 1, 0, 0),
                10,
                10
        );

        assertThatThrownBy(() -> repository.search(
                condition,
                cursor,
                21
        )).isInstanceOf(
                com.gift.gift.domain.product.exception.ProductException.class
        );
    }

    @Test
    @DisplayName("조회 개수가 허용 범위를 벗어나면 거부한다")
    void search_rejectsInvalidFetchCount() {
        var condition = new ProductSearchCondition(
                "세트",
                List.of(),
                ProductSort.POPULAR
        );

        for (int fetchCount : new int[]{0, 22}) {
            assertThatThrownBy(() -> repository.search(
                    condition,
                    null,
                    fetchCount
            ))
                    .isInstanceOf(
                            org.springframework.dao.InvalidDataAccessApiUsageException.class
                    )
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(
                            "상품 조회 개수는 1 이상 21 이하여야 합니다."
                    );
        }
    }
}
