package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Transactional
class ProductImageRepositoryIntegrationTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 18, 12, 0);

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Product productA;
    private Product productB;
    private Product productWithoutImage;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        Category root = saveCategory(
                "대표 이미지 대분류-" + suffix,
                null
        );
        Category child = saveCategory(
                "대표 이미지 세부-" + suffix,
                root
        );

        productA = saveProduct(child, "대표 이미지 상품 A");
        productB = saveProduct(child, "대표 이미지 상품 B");
        productWithoutImage = saveProduct(child, "이미지 없는 상품");
    }

    @Test
    @DisplayName("여러 상품의 활성 이미지를 대표 이미지 우선순위로 한 번에 조회한다")
    void findThumbnailCandidatesInOrder() {
        ProductImage second = saveImage(
                productA,
                "products/a/second.jpg",
                2
        );
        ProductImage first = saveImage(
                productA,
                "products/a/first.jpg",
                1
        );
        ProductImage nullOld = saveImage(
                productA,
                "products/a/null-old.jpg",
                null
        );
        ProductImage nullNew = saveImage(
                productA,
                "products/a/null-new.jpg",
                null
        );
        ProductImage deleted = saveImage(
                productA,
                "products/a/deleted.jpg",
                0
        );
        ProductImage productBFirst = saveImage(
                productB,
                "products/b/first.jpg",
                1
        );

        entityManager.flush();

        updateCreatedAt(second.getId(), BASE_TIME.plusMinutes(2));
        updateCreatedAt(first.getId(), BASE_TIME.plusMinutes(1));
        updateCreatedAt(nullOld.getId(), BASE_TIME.plusMinutes(3));
        updateCreatedAt(nullNew.getId(), BASE_TIME.plusMinutes(4));
        updateCreatedAt(productBFirst.getId(), BASE_TIME.plusMinutes(1));
        markDeleted(deleted.getId());

        entityManager.flush();
        entityManager.clear();

        List<ProductImageProjection> result =
                productImageRepository.findThumbnailCandidates(
                        List.of(
                                productWithoutImage.getId(),
                                productB.getId(),
                                productA.getId()
                        )
                );

        assertThat(result)
                .extracting(ProductImageProjection::objectKey)
                .containsExactly(
                        "products/a/first.jpg",
                        "products/a/second.jpg",
                        "products/a/null-old.jpg",
                        "products/a/null-new.jpg",
                        "products/b/first.jpg"
                );

        assertThat(result)
                .extracting(ProductImageProjection::productId)
                .containsExactly(
                        productA.getId(),
                        productA.getId(),
                        productA.getId(),
                        productA.getId(),
                        productB.getId()
                );
    }

    @Test
    @DisplayName("여러 상품의 대표 이미지 후보를 단일 쿼리로 조회한다")
    void findThumbnailCandidatesWithSingleQuery() {
        saveImage(productA, "products/a/main.jpg", 1);
        saveImage(productB, "products/b/main.jpg", 1);

        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        List<ProductImageProjection> result =
                productImageRepository.findThumbnailCandidates(
                        List.of(
                                productA.getId(),
                                productB.getId(),
                                productWithoutImage.getId()
                        )
                );

        assertThat(result).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("상품 ID 목록이 비어 있으면 쿼리 없이 빈 결과를 반환한다")
    void returnEmptyWithoutQuery() {
        Statistics statistics = entityManagerFactory
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        List<ProductImageProjection> result =
                productImageRepository.findThumbnailCandidates(List.of());

        assertThat(result).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    private Category saveCategory(String name, Category parent) {
        Category category = new Category(name, parent);
        entityManager.persist(category);
        return category;
    }

    private Product saveProduct(Category category, String name) {
        Product product = new Product(
                category,
                name,
                "대표 이미지 테스트 브랜드",
                null,
                BigDecimal.valueOf(10000),
                10
        );
        entityManager.persist(product);
        return product;
    }

    private ProductImage saveImage(
            Product product,
            String objectKey,
            Integer sortOrder
    ) {
        ProductImage image = new ProductImage(
                product,
                objectKey,
                sortOrder
        );
        entityManager.persist(image);
        return image;
    }

    private void updateCreatedAt(Long imageId, LocalDateTime createdAt) {
        entityManager.createQuery("""
                UPDATE ProductImage i
                SET i.createdAt = :createdAt
                WHERE i.id = :imageId
                """)
                .setParameter("createdAt", createdAt)
                .setParameter("imageId", imageId)
                .executeUpdate();
    }

    private void markDeleted(Long imageId) {
        entityManager.createQuery("""
                UPDATE ProductImage i
                SET i.deletedAt = :deletedAt
                WHERE i.id = :imageId
                """)
                .setParameter("deletedAt", BASE_TIME.plusDays(1))
                .setParameter("imageId", imageId)
                .executeUpdate();
    }
}
