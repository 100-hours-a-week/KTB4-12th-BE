package com.gift.gift.domain.product.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.query.ProductPage;
import com.gift.gift.domain.product.query.ProductPageAssembler;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.domain.product.repository.ProductSummaryProjection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductCursorPaginationIntegrationTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 17, 12, 0);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCursorCodec cursorCodec;

    @Autowired
    private ProductPageAssembler pageAssembler;

    private Category category;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        Category root = new Category("커서 대분류-" + suffix, null);
        entityManager.persist(root);

        category = new Category("커서 세부-" + suffix, root);
        entityManager.persist(category);

        categoryId = category.getId();
    }

    @ParameterizedTest
    @EnumSource(
            value = ProductSort.class,
            names = "AI_RECOMMENDED",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("정렬 우선순위와 동점일 때 ID 내림차순을 적용한다")
    void orderBySortAndId(ProductSort sort) {
        Long a = saveProduct(100, 1, BASE_TIME);
        Long b = saveProduct(90, 100, BASE_TIME);
        Long c = saveProduct(100, 2, BASE_TIME);
        Long d = saveProduct(100, 2, BASE_TIME.plusDays(1));
        Long e = saveProduct(100, 2, BASE_TIME.plusDays(1));

        entityManager.flush();
        entityManager.clear();

        List<Long> expected = switch (sort) {
            case AI_RECOMMENDED -> throw new IllegalArgumentException(
                    "AI 추천 정렬은 일반 상품 정렬 테스트 대상이 아닙니다."
            );
            case POPULAR -> List.of(e, d, c, a, b);
            case MOST_GIFTED -> List.of(b, e, d, c, a);
            case NEWEST -> List.of(e, d, c, b, a);
        };

        assertThat(productRepository.searchProducts(condition(sort), 21))
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("정렬을 지정하지 않으면 인기순으로 조회한다")
    void usePopularByDefault() {
        Long popular = saveProduct(100, 1, BASE_TIME);
        Long mostGifted = saveProduct(1, 100, BASE_TIME);

        entityManager.flush();
        entityManager.clear();

        ProductSearchCondition condition = new ProductSearchCondition(
                null,
                List.of(categoryId),
                null
        );

        assertThat(condition.sort()).isEqualTo(ProductSort.POPULAR);

        assertThat(productRepository.searchProducts(condition, 21))
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(popular, mostGifted);
    }

    @ParameterizedTest
    @MethodSource("pageCases")
    @DisplayName("정렬별 페이지 경계와 전체 상품의 중복·누락을 검증한다")
    void traverseAllPages(ProductSort sort, int totalCount) {
        List<Fixture> fixtures = new ArrayList<>();

        for (int index = 0; index < totalCount; index++) {
            int views = index % 3;
            int sales = index % 4;
            LocalDateTime createdAt = BASE_TIME.plusDays(index % 2);

            Long id = saveProduct(views, sales, createdAt);
            fixtures.add(new Fixture(id, views, sales, createdAt));
        }

        entityManager.flush();
        entityManager.clear();

        List<Long> expected = fixtures.stream()
                .sorted(expectedOrder(sort))
                .map(Fixture::id)
                .toList();

        ProductSearchCondition condition = condition(sort);
        List<Long> collected = new ArrayList<>();
        String nextCursor = null;
        int pageCount = 0;
        int expectedPages = Math.max(1, (totalCount + 19) / 20);

        while (true) {
            pageCount++;

            // 커서 진행 오류로 테스트가 무한 반복되는 것을 방지한다.
            assertThat(pageCount).isLessThanOrEqualTo(expectedPages);

            ProductCursor cursor =
                    cursorCodec.decode(nextCursor, condition);

            List<ProductSummaryProjection> fetched =
                    productRepository.searchProducts(
                            condition,
                            cursor,
                            ProductPageAssembler.FETCH_COUNT
                    );

            int remaining = totalCount - collected.size();

            assertThat(fetched)
                    .hasSize(Math.min(21, remaining));

            ProductPage page = pageAssembler.assemble(fetched, condition);

            int from = collected.size();
            int to = Math.min(from + 20, totalCount);

            List<Long> pageIds = page.items().stream()
                    .map(ProductSummaryProjection::productId)
                    .toList();

            assertThat(pageIds)
                    .containsExactlyElementsOf(expected.subList(from, to));

            boolean expectedHasNext = to < totalCount;

            assertThat(page.hasNext()).isEqualTo(expectedHasNext);

            collected.addAll(pageIds);

            if (!expectedHasNext) {
                assertThat(page.nextCursor()).isNull();
                break;
            }

            assertThat(page.nextCursor()).isNotBlank();

            ProductCursor next = cursorCodec.decode(
                    page.nextCursor(),
                    condition
            );

            assertThat(next.productId())
                    .isEqualTo(page.items().getLast().productId());

            nextCursor = page.nextCursor();
        }

        assertThat(pageCount).isEqualTo(expectedPages);
        assertThat(collected)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @EnumSource(
            value = ProductSort.class,
            names = "AI_RECOMMENDED",
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("모든 정렬값이 같아도 ID로 다음 페이지를 이어간다")
    void paginateWhenAllSortValuesAreEqual(ProductSort sort) {
        List<Long> ids = new ArrayList<>();

        for (int index = 0; index < 21; index++) {
            ids.add(saveProduct(10, 10, BASE_TIME));
        }

        entityManager.flush();
        entityManager.clear();

        List<Long> expected = ids.stream()
                .sorted(Comparator.reverseOrder())
                .toList();

        ProductSearchCondition condition = condition(sort);

        ProductPage first = pageAssembler.assemble(
                productRepository.searchProducts(condition, 21),
                condition
        );

        assertThat(first.items())
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyElementsOf(expected.subList(0, 20));

        assertThat(first.hasNext()).isTrue();

        ProductCursor cursor = cursorCodec.decode(
                first.nextCursor(),
                condition
        );

        ProductPage second = pageAssembler.assemble(
                productRepository.searchProducts(condition, cursor, 21),
                condition
        );

        assertThat(second.items())
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(expected.getLast());

        assertThat(second.hasNext()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    private static Stream<Arguments> pageCases() {
        return Stream.of(ProductSort.values())
                .filter(sort -> sort != ProductSort.AI_RECOMMENDED)
                .flatMap(sort -> Stream.of(0, 20, 21, 40, 41)
                        .map(count -> Arguments.of(sort, count)));
    }

    private ProductSearchCondition condition(ProductSort sort) {
        return new ProductSearchCondition(
                null,
                List.of(categoryId),
                sort
        );
    }

    private Long saveProduct(
            int views,
            int sales,
            LocalDateTime createdAt
    ) {
        Product product = new Product(
                category,
                "커서 테스트 상품",
                "테스트 브랜드",
                null,
                BigDecimal.valueOf(10000),
                10
        );

        entityManager.persist(product);
        entityManager.flush();

        // 정렬 테스트에 필요한 통계와 시간을 DB에 명시적으로 설정한다.
        entityManager.createQuery("""
                UPDATE Product p
                SET p.views = :views,
                    p.sales = :sales,
                    p.createdAt = :createdAt
                WHERE p.id = :id
                """)
                .setParameter("views", views)
                .setParameter("sales", sales)
                .setParameter("createdAt", createdAt)
                .setParameter("id", product.getId())
                .executeUpdate();

        return product.getId();
    }

    private static Comparator<Fixture> expectedOrder(ProductSort sort) {
        Comparator<Fixture> ascending = switch (sort) {
            case AI_RECOMMENDED -> throw new IllegalArgumentException(
                    "AI 추천 정렬은 일반 상품 정렬 테스트 대상이 아닙니다."
            );
            case POPULAR -> Comparator.comparingInt(Fixture::views)
                    .thenComparingInt(Fixture::sales)
                    .thenComparing(Fixture::createdAt)
                    .thenComparing(Fixture::id);

            case MOST_GIFTED -> Comparator.comparingInt(Fixture::sales)
                    .thenComparingInt(Fixture::views)
                    .thenComparing(Fixture::createdAt)
                    .thenComparing(Fixture::id);

            case NEWEST -> Comparator.comparing(Fixture::createdAt)
                    .thenComparing(Fixture::id);
        };

        return ascending.reversed();
    }

    private record Fixture(
            Long id,
            int views,
            int sales,
            LocalDateTime createdAt
    ) {
    }
}
