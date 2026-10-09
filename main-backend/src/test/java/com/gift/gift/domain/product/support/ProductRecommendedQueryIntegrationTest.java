package com.gift.gift.domain.product.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.transaction.TestTransaction;

import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorCodec;
import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductListResponse;
import com.gift.gift.domain.product.dto.response.ProductSummaryResponse;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.exception.ProductException;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.product.repository.ProductSearchCondition;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.domain.product.repository.ProductSummaryProjection;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientRecommendedProduct;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductRecommendedQueryIntegrationTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("Password1!");
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductQueryService service;
    @Autowired
    private ProductCursorCodec codec;
    @Autowired
    private MockMvc mockMvc;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> categoryIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private boolean committedFixtures;

    private User owner;
    private User recipient;
    private RecipientProfile profile;
    private Category category;
    private Category otherCategory;

    @BeforeEach
    void setUp() {
        owner = user();
        recipient = user();
        entityManager.persist(new Friendship(owner, recipient));
        profile = new RecipientProfile(recipient);
        profile.createNextSourceVersion();
        profile.markCompleted(1L);
        entityManager.persist(profile);
        Category root = new Category("추천 대분류 " + UUID.randomUUID(), null);
        entityManager.persist(root);
        category = new Category("추천 세부 " + UUID.randomUUID(), root);
        otherCategory = new Category("다른 세부 " + UUID.randomUUID(), root);
        entityManager.persist(category);
        entityManager.persist(otherCategory);
        categoryIds.addAll(List.of(root.getId(), category.getId(), otherCategory.getId()));
    }

    @Test
    @DisplayName("추천 순위를 우선 적용하고 인기순의 판매·저장 시각·ID 동률을 처리한다")
    void recommendationsPrecedePopularWithStableTieBreakers() {
        Product second = product(category, "크림", 1000, 1000, BASE_TIME);
        Product first = product(category, "크림", 0, 0, BASE_TIME);
        recommend(second, 2, 1);
        recommend(first, 1, 1);
        Product oldest = product(category, "크림", 100, 5, BASE_TIME);
        Product newer = product(category, "크림", 100, 5, BASE_TIME.plusDays(1));
        Product newerId = product(category, "크림", 100, 5, BASE_TIME.plusDays(1));
        Product sold = product(category, "크림", 100, 6, BASE_TIME);
        Product viewed = product(category, "크림", 101, 0, BASE_TIME);

        assertThat(query(null, List.of(category.getId())))
                .extracting(ProductSummaryProjection::productId)
                .containsExactly(first.getId(), second.getId(), viewed.getId(), sold.getId(),
                        newerId.getId(), newer.getId(), oldest.getId());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 25})
    @DisplayName("검색어·카테고리 없는 AI 조회도 추천과 인기 상품을 SQL 오류 없이 끝까지 연결한다")
    void paginatesWithoutQueryOrCategoryFilters(int recommendedCount) throws Exception {
        List<Long> expected = new ArrayList<>();
        for (int rank = 1; rank <= recommendedCount; rank++) {
            Product recommended = product(category, "크림", rank, 0, BASE_TIME);
            recommend(recommended, rank, 1);
            expected.add(recommended.getId());
        }
        for (int index = 0; index < 25; index++) {
            expected.add(product(otherCategory, "텀블러", 1000 - index, 0, BASE_TIME).getId());
        }
        entityManager.flush();

        // 기존 카테고리 필터의 닫는 괄호가 SQL 공백 누락을 가렸으므로 필터를 모두 생략한다.
        mockMvc.perform(get("/products")
                        .param("recipientUserId", recipient.getId().toString())
                        .param("sort", "AI_RECOMMENDED")
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSort").value("AI_RECOMMENDED"))
                .andExpect(jsonPath("$.data.products.length()").value(20))
                .andExpect(jsonPath("$.data.products[0].productId").value(expected.getFirst()))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(true));

        List<Long> actual = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 5; page++) {
            ProductListResponse response = response(null, null, cursor);
            assertThat(response.appliedSort()).isEqualTo(ProductSort.AI_RECOMMENDED);
            actual.addAll(response.products().stream().map(ProductSummaryResponse::productId).toList());
            cursor = response.pagination().nextCursor();
            if (!response.pagination().hasNext()) {
                assertThat(cursor).isNull();
                break;
            }
            ProductCursor decoded = codec.decode(cursor, condition(null, List.of()));
            assertThat(decoded.query()).isEmpty();
            assertThat(decoded.categoryIds()).isEmpty();
            assertThat(decoded.productId()).isEqualTo(response.products().getLast().productId());
        }
        assertThat(actual).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("삭제와 품절 상품은 추천 및 인기 영역 모두에서 제외한다")
    void excludesDeletedAndSoldOutProducts() {
        Product valid = product(category, "크림", 1, 0, BASE_TIME);
        Product deleted = product(category, "크림", 2, 0, BASE_TIME);
        Product soldOut = product(category, "크림", 3, 0, BASE_TIME);
        Product generalDeleted = product(category, "크림", 4, 0, BASE_TIME);
        Product generalSoldOut = product(category, "크림", 5, 0, BASE_TIME);
        recommend(valid, 3, 1);
        recommend(deleted, 1, 1);
        recommend(soldOut, 2, 1);
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE products SET deleted_at = NOW(6) WHERE id IN (:ids)")
                .setParameter("ids", List.of(deleted.getId(), generalDeleted.getId())).executeUpdate();
        entityManager.createNativeQuery("UPDATE products SET quantity = 0 WHERE id IN (:ids)")
                .setParameter("ids", List.of(soldOut.getId(), generalSoldOut.getId())).executeUpdate();

        assertThat(query(null, List.of(category.getId())))
                .extracting(ProductSummaryProjection::productId).containsExactly(valid.getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("검색어와 단일·복수 카테고리 필터를 추천 및 인기 영역에 동일하게 적용한다")
    void filtersRecommendationsAndGeneralProducts(boolean multipleCategories) {
        Product first = product(category, "수분크림", 0, 0, BASE_TIME);
        Product second = product(otherCategory, "수분크림", 0, 0, BASE_TIME);
        Product excluded = product(category, "텀블러", 0, 0, BASE_TIME);
        Product popular = product(category, "수분크림", 100, 0, BASE_TIME);
        Product wrongKeyword = product(category, "텀블러", 1000, 0, BASE_TIME);
        recommend(first, 2, 1);
        recommend(second, 1, 1);
        recommend(excluded, 3, 1);
        List<Long> categories = multipleCategories
                ? List.of(category.getId(), otherCategory.getId()) : List.of(category.getId());
        List<Long> expected = multipleCategories
                ? List.of(second.getId(), first.getId(), popular.getId())
                : List.of(first.getId(), popular.getId());

        // InnoDB FULLTEXT는 커밋된 상품만 색인하므로 검색 검증 전에 데이터를 커밋한다.
        entityManager.flush();
        committedFixtures = true;
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        assertThat(query("수분크림", categories)).extracting(ProductSummaryProjection::productId)
                .containsExactlyElementsOf(expected).doesNotContain(wrongKeyword.getId());
        assertThat(response("수분크림", categories, null).appliedSort()).isEqualTo(ProductSort.AI_RECOMMENDED);
    }

    @Test
    @DisplayName("추천 전체가 카테고리 필터에서 제외되면 인기순으로 대체한다")
    void fallsBackWhenAllRecommendationsFilteredOut() {
        recommend(product(otherCategory, "크림", 1000, 0, BASE_TIME), 1, 1);
        Product popular = product(category, "크림", 10, 0, BASE_TIME);
        Product lessPopular = product(category, "크림", 1, 0, BASE_TIME);

        ProductListResponse response = response(null, List.of(category.getId()), null);

        assertThat(response.appliedSort()).isEqualTo(ProductSort.POPULAR);
        assertThat(response.products()).extracting(ProductSummaryResponse::productId)
                .containsExactly(popular.getId(), lessPopular.getId());
    }

    @Test
    @DisplayName("미래 요청 버전의 추천을 사용하지 않고 마지막 정상 분석 버전만 조회한다")
    void usesAnalyzedVersionInsteadOfRequestedVersion() {
        Product old = product(category, "크림", 0, 0, BASE_TIME);
        Product future = product(category, "크림", 100, 0, BASE_TIME);
        recommend(old, 1, 1);
        profile.createNextSourceVersion();
        profile.markPending(BASE_TIME);
        recommend(future, 1, 2);

        assertThat(query(null, List.of(category.getId())))
                .extracting(ProductSummaryProjection::productId).containsExactly(old.getId(), future.getId());
        assertThat(query(null, List.of(category.getId())))
                .extracting(ProductSummaryProjection::rankOrder).containsExactly(1, null);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 19, 20, 21, 30})
    @DisplayName("추천 경계 전후와 인기 영역의 페이지 이동에서 상품 중복·누락이 없다")
    void paginatesAcrossBothRegions(int recommendedCount) {
        List<Long> expected = new ArrayList<>();
        for (int rank = 1; rank <= recommendedCount; rank++) {
            Product product = product(category, "크림", rank, 0, BASE_TIME);
            recommend(product, rank, 1);
            expected.add(product.getId());
        }
        for (int index = 0; index < 45; index++) {
            expected.add(product(category, "크림", 1000 - index, 0, BASE_TIME).getId());
        }
        List<Long> actual = new ArrayList<>();
        String cursor = null;
        boolean sawRecommendedCursor = false;
        boolean sawPopularCursor = false;
        for (int page = 0; page < 10; page++) {
            ProductListResponse response = response(null, List.of(category.getId()), cursor);
            assertThat(response.appliedSort()).isEqualTo(ProductSort.AI_RECOMMENDED);
            assertThat(response.products()).hasSizeLessThanOrEqualTo(20);
            actual.addAll(response.products().stream().map(ProductSummaryResponse::productId).toList());
            cursor = response.pagination().nextCursor();
            if (!response.pagination().hasNext()) {
                assertThat(cursor).isNull();
                break;
            }
            ProductCursor decoded = codec.decode(cursor, condition(null, List.of(category.getId())));
            assertThat(decoded.productId()).isEqualTo(response.products().getLast().productId());
            sawRecommendedCursor |= Boolean.TRUE.equals(decoded.recommendedRegion());
            sawPopularCursor |= Boolean.FALSE.equals(decoded.recommendedRegion());
        }
        assertThat(actual).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
        assertThat(sawPopularCursor).isTrue();
        if (recommendedCount >= 20) {
            assertThat(sawRecommendedCursor).isTrue();
        }
    }

    @Test
    @DisplayName("추천 버전이 변경되면 기존 추천 커서를 거부한다")
    void rejectsCursorAfterVersionChange() {
        recommend(product(category, "크림", 0, 0, BASE_TIME), 1, 1);
        addPopularProducts();
        String cursor = response(null, List.of(category.getId()), null).pagination().nextCursor();
        profile.createNextSourceVersion();
        profile.markCompleted(2L);
        assertInvalidCursor(cursor);
    }

    @Test
    @DisplayName("추천이 없던 인기순 대체 커서도 첫 정상 분석 결과가 생기면 만료된다")
    void rejectsFallbackCursorWhenFirstResultsArrive() {
        RecipientProfile fresh = new RecipientProfile(user());
        entityManager.persist(fresh);
        entityManager.persist(new Friendship(owner, fresh.getRecipient()));
        recipient = fresh.getRecipient();
        profile = fresh;
        addPopularProducts();
        ProductListResponse first = response(null, List.of(category.getId()), null);
        assertThat(first.appliedSort()).isEqualTo(ProductSort.POPULAR);
        profile.createNextSourceVersion();
        profile.markCompleted(1L);
        assertInvalidCursor(first.pagination().nextCursor());
    }

    @Test
    @DisplayName("HTTP 응답에 실제 추천 정렬과 추천 상품을 반환하고 다른 수신자 커서는 400으로 거부한다")
    void returnsRecommendedResponseAndRejectsDifferentRecipientCursor() throws Exception {
        Product recommended = product(category, "크림", 0, 0, BASE_TIME);
        recommend(recommended, 1, 1);
        addPopularProducts();
        mockMvc.perform(get("/products").param("recipientUserId", recipient.getId().toString())
                        .param("categoryIds", category.getId().toString())
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSort").value("AI_RECOMMENDED"))
                .andExpect(jsonPath("$.data.products[0].productId").value(recommended.getId()));
        String cursor = response(null, List.of(category.getId()), null).pagination().nextCursor();
        User other = user();
        entityManager.persist(new Friendship(owner, other));
        RecipientProfile otherProfile = new RecipientProfile(other);
        otherProfile.createNextSourceVersion();
        otherProfile.markCompleted(1L);
        entityManager.persist(otherProfile);
        entityManager.persist(new RecipientRecommendedProduct(otherProfile, recommended, 1, 1L));
        entityManager.flush();
        mockMvc.perform(get("/products").param("recipientUserId", other.getId().toString())
                        .param("categoryIds", category.getId().toString()).param("cursor", cursor)
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @AfterEach
    void cleanUpCommittedFixtures() {
        if (TestTransaction.isActive()) {
            TestTransaction.flagForRollback();
            TestTransaction.end();
        }
        if (!committedFixtures) {
            return;
        }
        // 검색을 위해 커밋한 데이터는 FK 의존 순서로 삭제해 후속 테스트와 격리한다.
        TestTransaction.start();
        try {
            entityManager.createNativeQuery("DELETE FROM recipient_recommended_products WHERE recipient_id IN (:ids)")
                    .setParameter("ids", userIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM recipient_profiles WHERE recipient_id IN (:ids)")
                    .setParameter("ids", userIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM friendships WHERE user_id_1 IN (:ids) OR user_id_2 IN (:ids)")
                    .setParameter("ids", userIds).executeUpdate();
            entityManager.createNativeQuery("DELETE FROM products WHERE id IN (:ids)")
                    .setParameter("ids", productIds).executeUpdate();
            for (int i = categoryIds.size() - 1; i >= 0; i--) {
                entityManager.createNativeQuery("DELETE FROM categories WHERE id = :id")
                        .setParameter("id", categoryIds.get(i)).executeUpdate();
            }
            entityManager.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", userIds).executeUpdate();
            TestTransaction.flagForCommit();
        } finally {
            TestTransaction.end();
        }
    }

    private User user() {
        User user = new User(UUID.randomUUID() + "@test.com", PASSWORD_HASH, "수신자", LocalDate.of(2000, 1, 1));
        entityManager.persist(user);
        userIds.add(user.getId());
        return user;
    }

    private Product product(Category category, String name, int views, int sales, LocalDateTime createdAt) {
        Product product = new Product(category, name, "테스트 브랜드", null, BigDecimal.valueOf(10000), 10);
        entityManager.persist(product);
        productIds.add(product.getId());
        entityManager.flush();
        entityManager.createNativeQuery("""
                UPDATE products SET views = :views, sales = :sales, created_at = :time WHERE id = :id
                """)
                .setParameter("views", views).setParameter("sales", sales)
                .setParameter("time", createdAt).setParameter("id", product.getId()).executeUpdate();
        return product;
    }

    private void recommend(Product product, int rank, long version) {
        entityManager.persist(new RecipientRecommendedProduct(profile, product, rank, version));
    }

    private ProductSearchCondition condition(String query, List<Long> categories) {
        return new ProductSearchCondition(query, categories, ProductSort.AI_RECOMMENDED,
                ProductSort.AI_RECOMMENDED, recipient.getId(), profile.getAnalyzedSourceVersion());
    }

    private List<ProductSummaryProjection> query(String query, List<Long> categories) {
        entityManager.flush();
        return productRepository.searchProducts(condition(query, categories), 21);
    }

    private ProductListResponse response(String query, List<Long> categories, String cursor) {
        entityManager.flush();
        return service.getProducts(
                new ProductListRequest(query, categories, null, recipient.getId(), cursor), owner.getId());
    }

    private void addPopularProducts() {
        for (int i = 0; i < 25; i++) {
            product(category, "크림", 100 - i, 0, BASE_TIME);
        }
    }

    private void assertInvalidCursor(String cursor) {
        assertThat(cursor).isNotBlank();
        assertThatThrownBy(() -> response(null, List.of(category.getId()), cursor))
                .isInstanceOfSatisfying(ProductException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_CURSOR));
    }
}
