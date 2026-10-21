package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.transaction.TestTransaction;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class ProductSearchRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    private final List<Long> fixtureProductIds = new ArrayList<>();
    private final List<Long> fixtureCategoryIds = new ArrayList<>();

    private Long skincareId;
    private Long bodycareId;
    private Long creamId;
    private Long tonerId;
    private Long handCreamId;
    private Long cakeId;
    private Long deletedProductId;

    @BeforeEach
    void setUp() {
        // 조건 없는 조회도 검증하므로 비어 있는 전용 테스트 DB를 사용한다.
        assertThat(productRepository.count())
                .as("상품이 없는 전용 테스트 DB에서 실행해야 합니다.")
                .isZero();

        String suffix = UUID.randomUUID().toString();

        Category root = saveCategory("테스트 대분류-" + suffix, null);
        Category skincare = saveCategory("스킨케어-" + suffix, root);
        Category bodycare = saveCategory("바디케어-" + suffix, root);
        Category dessert = saveCategory("디저트-" + suffix, root);

        skincareId = skincare.getId();
        bodycareId = bodycare.getId();

        creamId = saveProduct(skincare, "수분 크림", "브랜드A");
        tonerId = saveProduct(skincare, "진정 토너", "크림연구소");
        handCreamId = saveProduct(bodycare, "핸드 크림", "브랜드B");
        cakeId = saveProduct(dessert, "초콜릿 케이크", "디저트샵");
        deletedProductId = saveProduct(
                skincare, "삭제 크림", "크림연구소"
        );

        entityManager.flush();

        entityManager.createQuery("""
                UPDATE Product p
                SET p.deletedAt = :deletedAt
                WHERE p.id = :productId
                """)
                .setParameter("deletedAt", LocalDateTime.now())
                .setParameter("productId", deletedProductId)
                .executeUpdate();

        entityManager.clear();
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        if (TestTransaction.isActive()) {
            TestTransaction.flagForRollback();
            TestTransaction.end();
        }
        if (fixtureProductIds.isEmpty() && fixtureCategoryIds.isEmpty()) {
            return;
        }

        TestTransaction.start();
        try {
            if (!fixtureProductIds.isEmpty()) {
                entityManager.createQuery("DELETE FROM Product p WHERE p.id IN :ids")
                        .setParameter("ids", fixtureProductIds)
                        .executeUpdate();
            }
            // 자식 카테고리를 먼저 지워 외래 키 순서를 지킨다.
            for (int index = fixtureCategoryIds.size() - 1; index >= 0; index--) {
                entityManager.createQuery("DELETE FROM Category c WHERE c.id = :id")
                        .setParameter("id", fixtureCategoryIds.get(index))
                        .executeUpdate();
            }
            TestTransaction.flagForCommit();
        } finally {
            TestTransaction.end();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("검색어가 없거나 공백이면 모든 활성 상품을 조회한다")
    void searchWithoutKeyword(String keyword) {
        assertIds(
                search(keyword, null, 21),
                creamId, tonerId, handCreamId, cakeId
        );
    }

    @Test
    @DisplayName("빈 카테고리 목록이면 카테고리 제한 없이 조회한다")
    void searchWithEmptyCategories() {
        assertIds(
                search(null, List.of(), 21),
                creamId, tonerId, handCreamId, cakeId
        );
    }

    @Test
    @DisplayName("상품명 일부가 일치하는 상품을 조회한다")
    void searchByProductName() {
        assertIds(search("수분", null, 21), creamId);
    }

    @Test
    @DisplayName("공백 포함 구문과 붙어 있는 상품명 내부를 FULLTEXT로 검색한다")
    void searchPhraseAndCompoundSubstring() {
        Category skincare = entityManager.find(Category.class, skincareId);
        Long compoundId = saveProduct(skincare, "수분크림", "브랜드B");

        assertIds(search("수분 크림", null, 21), creamId);
        assertIds(search("분크", null, 21), compoundId);
    }

    @Test
    @DisplayName("ngram 크기 2에서 한 글자 검색은 결과가 없다")
    void searchSingleCharacterHasNoToken() {
        assertThat(search("크", null, 21)).isEmpty();
    }

    @Test
    @DisplayName("브랜드명 일부가 일치하는 상품을 조회한다")
    void searchByBrandName() {
        assertIds(search("연구소", null, 21), tonerId);
    }

    @Test
    @DisplayName("상품명 또는 브랜드명이 일치하면 조회한다")
    void searchByProductNameOrBrandName() {
        assertIds(
                search("크림", null, 21),
                creamId, tonerId, handCreamId
        );
    }

    @Test
    @DisplayName("검색어 앞뒤 공백을 제거하고 조회한다")
    void searchWithTrimmedKeyword() {
        assertIds(search("  수분  ", null, 21), creamId);
    }

    @Test
    @DisplayName("일치하는 상품이 없으면 빈 목록을 반환한다")
    void searchWithoutMatches() {
        assertThat(search("존재하지않는검색어", null, 21)).isEmpty();
    }

    @Test
    @DisplayName("단일 세부 카테고리로 필터링한다")
    void searchBySingleCategory() {
        assertIds(
                search(null, List.of(skincareId), 21),
                creamId, tonerId
        );
    }

    @Test
    @DisplayName("복수 세부 카테고리 중 하나에 속하면 조회한다")
    void searchByMultipleCategories() {
        assertIds(
                search(null, List.of(skincareId, bodycareId), 21),
                creamId, tonerId, handCreamId
        );
    }

    @Test
    @DisplayName("검색어와 카테고리 조건을 모두 만족해야 조회한다")
    void searchByKeywordAndCategory() {
        assertIds(
                search("크림", List.of(bodycareId), 21),
                handCreamId
        );
    }

    @Test
    @DisplayName("검색어가 일치해도 삭제된 상품은 제외한다")
    void excludeDeletedProducts() {
        assertThat(search("삭제", null, 21)).isEmpty();

        assertThat(search("크림", null, 21))
                .extracting(ProductSummaryProjection::productId)
                .doesNotContain(deletedProductId);
    }

    @Test
    @DisplayName("목록 Projection의 각 필드를 올바르게 조회한다")
    void mapProjectionFields() {
        List<ProductSummaryProjection> result =
                search("수분", null, 21);

        assertThat(result).hasSize(1);

        ProductSummaryProjection product = result.getFirst();

        assertThat(product.productId()).isEqualTo(creamId);
        assertThat(product.productName()).isEqualTo("수분 크림");
        assertThat(product.brandName()).isEqualTo("브랜드A");
        assertThat(product.price()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("서버에서 요청한 개수만큼만 조회한다")
    void limitResultsToRequestedCount() {
        List<ProductSummaryProjection> result =
                search(null, null, 2);

        assertThat(result).hasSize(2);
        assertThat(result)
                .extracting(ProductSummaryProjection::productId)
                .doesNotHaveDuplicates()
                .isSubsetOf(creamId, tonerId, handCreamId, cakeId);
    }

    @Test
    @DisplayName("조건에 맞는 상품이 22개여도 최대 21개만 조회한다")
    void limitResultsToTwentyOne() {
        Category skincare =
                entityManager.find(Category.class, skincareId);

        for (int index = 0; index < 22; index++) {
            saveProduct(
                    skincare,
                    "조회제한검증상품-" + index,
                    "제한검증브랜드"
            );
        }

        entityManager.flush();
        entityManager.clear();

        List<ProductSummaryProjection> result =
                search("조회제한검증상품", null, 21);

        assertThat(result).hasSize(21);
        assertThat(result)
                .extracting(ProductSummaryProjection::productId)
                .doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 22})
    @DisplayName("조회 개수가 1에서 21 사이가 아니면 거부한다")
    void rejectInvalidFetchCount(int fetchCount) {
        assertThatThrownBy(() -> search(null, null, fetchCount))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품 조회 개수는 1 이상 21 이하여야 합니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!"})
    @DisplayName("ngram 크기 2에서 특수문자 한 글자만 검색하면 결과가 없다")
    void searchSingleSpecialCharacterHasNoToken(String keyword) {
        Category skincare =
                entityManager.find(Category.class, skincareId);

        saveProduct(
                skincare,
                "특수" + keyword + "문자",
                "테스트브랜드"
        );

        saveProduct(skincare, "특수X문자", "테스트브랜드");

        entityManager.flush();
        entityManager.clear();

        assertThat(search(keyword, null, 21)).isEmpty();
    }

    private List<ProductSummaryProjection> search(
            String keyword,
            List<Long> categoryIds,
            int fetchCount
    ) {
        // InnoDB FULLTEXT는 flush만으로 신규 행을 검색할 수 없으므로 커밋한다.
        // Testcontainers 데이터만 커밋하고 @AfterEach에서 생성한 ID만 정리한다.
        entityManager.flush();
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        return productRepository.searchProducts(
                new ProductSearchCondition(keyword, categoryIds),
                fetchCount
        );
    }

    private Category saveCategory(String name, Category parent) {
        Category category = new Category(name, parent);
        entityManager.persist(category);
        fixtureCategoryIds.add(category.getId());
        return category;
    }

    private Long saveProduct(
            Category category,
            String name,
            String brand
    ) {
        Product product = new Product(
                category,
                name,
                brand,
                null,
                BigDecimal.valueOf(10000),
                10
        );

        entityManager.persist(product);
        fixtureProductIds.add(product.getId());
        return product.getId();
    }

    private void assertIds(
            List<ProductSummaryProjection> result,
            Long... expectedIds
    ) {
        assertThat(result)
                .extracting(ProductSummaryProjection::productId)
                .containsExactlyInAnyOrderElementsOf(
                        Stream.of(expectedIds).toList()
                );
    }
}
