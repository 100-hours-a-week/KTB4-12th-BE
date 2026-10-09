package com.gift.gift.domain.recommendation.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.CategoryRepository;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientRecommendedProduct;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class RecipientRecommendedProductRepositoryIntegrationTest {

    @Autowired
    private RecipientRecommendedProductRepository recommendationRepository;

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("수신자와 버전 및 순위 기준으로 추천 상품을 조회한다")
    void findAll_returnsProductsInRankOrder() {
        RecipientProfile profile = persistProfileWithVersion();
        Product first = persistProduct("첫 번째");
        Product second = persistProduct("두 번째");
        Product third = persistProduct("세 번째");

        recommendationRepository.saveAllAndFlush(List.of(
                recommendation(profile, third, 3),
                recommendation(profile, first, 1),
                recommendation(profile, second, 2)
        ));

        Long recipientId = profile.getRecipient().getId();
        long sourceVersion = profile.getSourceVersion();
        Long firstProductId = first.getId();
        Long secondProductId = second.getId();
        Long thirdProductId = third.getId();
        entityManager.clear();

        List<RecipientRecommendedProduct> found =
                recommendationRepository
                        .findAllByRecipientProfile_Recipient_IdAndSourceVersionOrderByRankOrderAsc(
                                recipientId,
                                sourceVersion
                        );

        assertThat(found)
                .extracting(RecipientRecommendedProduct::getRankOrder)
                .containsExactly(1, 2, 3);
        assertThat(found)
                .extracting(item -> item.getProduct().getId())
                .containsExactly(
                        firstProductId,
                        secondProductId,
                        thirdProductId
                );
    }

    @Test
    @DisplayName("같은 수신자와 버전에 같은 상품을 중복 저장할 수 없다")
    void save_rejectsDuplicateProductInSameVersion() {
        RecipientProfile profile = persistProfileWithVersion();
        Product product = persistProduct("중복 상품");

        recommendationRepository.saveAndFlush(
                recommendation(profile, product, 1)
        );

        assertThatThrownBy(() ->
                recommendationRepository.saveAndFlush(
                        recommendation(profile, product, 2)
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 수신자와 버전에 같은 순위를 중복 저장할 수 없다")
    void save_rejectsDuplicateRankInSameVersion() {
        RecipientProfile profile = persistProfileWithVersion();

        recommendationRepository.saveAndFlush(
                recommendation(
                        profile,
                        persistProduct("기존 상품"),
                        1
                )
        );

        assertThatThrownBy(() ->
                recommendationRepository.saveAndFlush(
                        recommendation(
                                profile,
                                persistProduct("다른 상품"),
                                1
                        )
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("추천 순위 범위를 벗어난 값을 DB CHECK 제약조건이 거부한다")
    void database_rejectsRankOutsideOneToThirty() {
        RecipientRecommendedProduct saved =
                persistRecommendation(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE recipient_recommended_products
                SET rank_order = 0
                WHERE id = ?
                """,
                saved.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("추천 결과의 음수 버전을 DB CHECK 제약조건이 거부한다")
    void database_rejectsNegativeSourceVersion() {
        RecipientRecommendedProduct saved =
                persistRecommendation(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE recipient_recommended_products
                SET source_version = -1
                WHERE id = ?
                """,
                saved.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("존재하지 않는 상품 ID를 FK가 거부한다")
    void database_rejectsNonexistentProduct() {
        RecipientRecommendedProduct saved =
                persistRecommendation(1);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE recipient_recommended_products
                SET product_id = ?
                WHERE id = ?
                """,
                Long.MAX_VALUE,
                saved.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 수신자 프로파일 ID를 FK가 거부한다")
    void database_rejectsNonexistentRecipientProfile() {
        Product product = persistProduct("FK 검증 상품");

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO recipient_recommended_products (
                    recipient_id,
                    product_id,
                    rank_order,
                    source_version,
                    created_at,
                    updated_at
                ) VALUES (?, ?, 1, 1, NOW(6), NOW(6))
                """,
                Long.MAX_VALUE,
                product.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("수신자의 기존 추천 상품을 모두 삭제한다")
    void deleteAllByRecipientId_deletesRecommendations() {
        RecipientProfile profile = persistProfileWithVersion();
        recommendationRepository.saveAllAndFlush(List.of(
                recommendation(profile, persistProduct("삭제 상품 1"), 1),
                recommendation(profile, persistProduct("삭제 상품 2"), 2)
        ));

        int deletedCount = recommendationRepository
                .deleteAllByRecipientId(
                        profile.getRecipient().getId()
                );

        assertThat(deletedCount).isEqualTo(2);
        assertThat(recommendationRepository
                .findAllByRecipientProfile_Recipient_IdAndSourceVersionOrderByRankOrderAsc(
                        profile.getRecipient().getId(),
                        profile.getSourceVersion()
                )
        ).isEmpty();
    }

    private RecipientRecommendedProduct persistRecommendation(
            int rankOrder
    ) {
        RecipientProfile profile = persistProfileWithVersion();
        Product product = persistProduct("추천 상품");
        return recommendationRepository.saveAndFlush(
                recommendation(profile, product, rankOrder)
        );
    }

    private RecipientRecommendedProduct recommendation(
            RecipientProfile profile,
            Product product,
            int rankOrder
    ) {
        return new RecipientRecommendedProduct(
                profile,
                product,
                rankOrder,
                profile.getSourceVersion()
        );
    }

    private RecipientProfile persistProfileWithVersion() {
        User recipient = userRepository.saveAndFlush(
                new User(
                        "recipient-" + UUID.randomUUID() + "@example.com",
                        "$2a$10$" + "a".repeat(53),
                        "수신자",
                        LocalDate.of(2000, 1, 1)
                )
        );

        RecipientProfile profile = new RecipientProfile(recipient);
        profile.createNextSourceVersion();
        return profileRepository.saveAndFlush(profile);
    }

    private Product persistProduct(String namePrefix) {
        String suffix = UUID.randomUUID().toString();
        Category root = categoryRepository.saveAndFlush(
                new Category("추천 대분류 " + suffix, null)
        );
        Category child = categoryRepository.saveAndFlush(
                new Category("추천 세부 " + suffix, root)
        );

        return productRepository.saveAndFlush(
                new Product(
                        child,
                        namePrefix + " " + suffix,
                        "테스트 브랜드",
                        null,
                        BigDecimal.valueOf(10_000),
                        10
                )
        );
    }
}
