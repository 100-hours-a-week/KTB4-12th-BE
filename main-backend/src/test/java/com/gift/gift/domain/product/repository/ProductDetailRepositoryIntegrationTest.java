package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest(properties = {
        "storage.s3.region=ap-northeast-2",
        "storage.s3.bucket=product-detail-test"
})
@Transactional
class ProductDetailRepositoryIntegrationTest {

    private static final LocalDateTime BASE_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private EntityManager entityManager;

    private Category category;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();

        Category root = new Category("대분류-" + suffix, null);
        entityManager.persist(root);

        category = new Category("세부-" + suffix, root);
        entityManager.persist(category);
    }

    @Test
    @DisplayName("활성 상품과 품절 상품은 조회하고 삭제 상품은 제외한다")
    void findProduct_includesSoldOutAndExcludesDeleted() {
        Product active = saveProduct(8);
        Product soldOut = saveProduct(0);
        Product deleted = saveProduct(3);

        entityManager.flush();

        entityManager.createQuery("""
                UPDATE Product p
                SET p.deletedAt = :deletedAt
                WHERE p.id = :id
                """)
                .setParameter("deletedAt", BASE_TIME)
                .setParameter("id", deleted.getId())
                .executeUpdate();

        entityManager.clear();

        assertThat(productRepository.findByIdAndDeletedAtIsNull(active.getId()))
                .isPresent();

        assertThat(productRepository.findByIdAndDeletedAtIsNull(soldOut.getId()))
                .hasValueSatisfying(
                        product -> assertThat(product.getQuantity()).isZero()
                );

        assertThat(productRepository.findByIdAndDeletedAtIsNull(deleted.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 상품은 빈 결과를 반환한다")
    void findProduct_returnsEmptyWhenMissing() {
        // 실제로 발급된 ID의 상품을 제거해 존재하지 않는 ID를 확보한다.
        Product product = saveProduct(8);
        Long productId = product.getId();

        entityManager.remove(product);
        entityManager.flush();
        entityManager.clear();

        assertThat(productRepository.findByIdAndDeletedAtIsNull(productId))
                .isEmpty();
    }

    @Test
    @DisplayName("활성 이미지만 표시 순서와 보조 정렬에 따라 반환한다")
    void findImages_filtersAndOrdersImages() {
        Product product = saveProduct(8);
        Product otherProduct = saveProduct(8);

        // 저장 순서와 표시 순서를 다르게 구성한다.
        ProductImage second = saveImage(product, 2);
        ProductImage first = saveImage(product, 1);

        ProductImage nullA = saveImage(product, null);
        ProductImage nullB = saveImage(product, null);
        ProductImage nullC = saveImage(product, null);

        ProductImage deleted = saveImage(product, 0);
        saveImage(otherProduct, 1);

        entityManager.flush();

        // 생성 시각이 ID 순서보다 우선하는지 검증한다.
        // nullB와 nullC는 시각이 같으므로 ID 오름차순으로 정렬된다.
        updateCreatedAt(nullA.getId(), BASE_TIME.plusMinutes(1));
        updateCreatedAt(nullB.getId(), BASE_TIME);
        updateCreatedAt(nullC.getId(), BASE_TIME);

        entityManager.createQuery("""
                UPDATE ProductImage i
                SET i.deletedAt = :deletedAt
                WHERE i.id = :id
                """)
                .setParameter("deletedAt", BASE_TIME)
                .setParameter("id", deleted.getId())
                .executeUpdate();

        entityManager.clear();

        List<ProductImage> result =
                productImageRepository.findActiveDetailImages(product.getId());

        assertThat(result)
                .extracting(ProductImage::getId)
                .containsExactly(
                        first.getId(),
                        second.getId(),
                        nullB.getId(),
                        nullC.getId(),
                        nullA.getId()
                );
    }

    @Test
    @DisplayName("이미지가 없는 상품은 빈 이미지 목록을 반환한다")
    void findImages_returnsEmptyWhenNoImages() {
        Product product = saveProduct(8);

        entityManager.flush();
        entityManager.clear();

        assertThat(
                productImageRepository.findActiveDetailImages(product.getId())
        ).isEmpty();
    }

    private Product saveProduct(int quantity) {
        Product product = new Product(
                category,
                "테스트 상품",
                "테스트 브랜드",
                null,
                new BigDecimal("32000"),
                quantity
        );
        entityManager.persist(product);
        return product;
    }

    private ProductImage saveImage(Product product, Integer sortOrder) {
        ProductImage image = new ProductImage(
                product,
                "products/test/" + UUID.randomUUID() + ".webp",
                sortOrder
        );
        entityManager.persist(image);
        return image;
    }

    private void updateCreatedAt(Long imageId, LocalDateTime createdAt) {
        entityManager.createQuery("""
                UPDATE ProductImage i
                SET i.createdAt = :createdAt
                WHERE i.id = :id
                """)
                .setParameter("createdAt", createdAt)
                .setParameter("id", imageId)
                .executeUpdate();
    }
}
