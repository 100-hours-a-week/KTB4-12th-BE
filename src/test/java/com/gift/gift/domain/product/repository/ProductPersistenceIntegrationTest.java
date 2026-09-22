package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductPersistenceIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("카테고리, 상품, 상품 이미지를 저장하고 연관관계를 조회한다")
    void saveAndFindProductPersistence() {
        String suffix = UUID.randomUUID().toString();

        Category rootCategory = categoryRepository.save(
                new Category("테스트 대분류 " + suffix, null)
        );

        Category childCategory = categoryRepository.save(
                new Category("테스트 세부 카테고리 " + suffix, rootCategory)
        );

        Product product = productRepository.save(
                new Product(
                        childCategory,
                        "테스트 상품",
                        "테스트 브랜드",
                        "영속성 매핑 테스트 상품",
                        BigDecimal.valueOf(59000),
                        10
                )
        );

        ProductImage productImage = productImageRepository.save(
                new ProductImage(
                        product,
                        "products/test/" + suffix + ".webp",
                        1
                )
        );

        entityManager.flush();

        Long rootCategoryId = rootCategory.getId();
        Long childCategoryId = childCategory.getId();
        Long productId = product.getId();
        Long productImageId = productImage.getId();

        entityManager.clear();

        Category foundChildCategory = categoryRepository.findById(childCategoryId)
                .orElseThrow();

        Product foundProduct = productRepository.findById(productId)
                .orElseThrow();

        ProductImage foundProductImage =
                productImageRepository.findById(productImageId)
                        .orElseThrow();

        assertThat(rootCategoryId).isNotNull();
        assertThat(foundChildCategory.getParent().getId())
                .isEqualTo(rootCategoryId);

        assertThat(foundProduct.getName()).isEqualTo("테스트 상품");
        assertThat(foundProduct.getBrand()).isEqualTo("테스트 브랜드");
        assertThat(foundProduct.getPrice())
                .isEqualByComparingTo("59000");
        assertThat(foundProduct.getQuantity()).isEqualTo(10);
        assertThat(foundProduct.getViews()).isZero();
        assertThat(foundProduct.getSales()).isZero();
        assertThat(foundProduct.getCreatedAt()).isNotNull();
        assertThat(foundProduct.getUpdatedAt()).isNotNull();

        assertThat(foundProductImage.getObjectKey())
                .isEqualTo("products/test/" + suffix + ".webp");
        assertThat(foundProductImage.getSortOrder()).isEqualTo(1);
        assertThat(foundProductImage.getCreatedAt()).isNotNull();
        assertThat(foundProductImage.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("상품의 카테고리 연관관계는 지연 로딩된다")
    void productCategoryIsLoadedLazily() {
        String suffix = UUID.randomUUID().toString();

        Category rootCategory = categoryRepository.save(
                new Category("LAZY 대분류 " + suffix, null)
        );

        Category childCategory = categoryRepository.save(
                new Category("LAZY 세부 카테고리 " + suffix, rootCategory)
        );

        Product product = productRepository.save(
                new Product(
                        childCategory,
                        "LAZY 테스트 상품",
                        "테스트 브랜드",
                        null,
                        BigDecimal.valueOf(10000),
                        5
                )
        );

        entityManager.flush();
        Long productId = product.getId();
        entityManager.clear();

        Product foundProduct = productRepository.findById(productId)
                .orElseThrow();

        assertThat(Hibernate.isInitialized(foundProduct.getCategory()))
                .isFalse();

        assertThat(foundProduct.getCategory().getName())
                .isEqualTo("LAZY 세부 카테고리 " + suffix);

        assertThat(Hibernate.isInitialized(foundProduct.getCategory()))
                .isTrue();
    }

    @Test
    @DisplayName("상품 이미지의 상품 연관관계는 지연 로딩된다")
    void productImageProductIsLoadedLazily() {
        String suffix = UUID.randomUUID().toString();

        Category rootCategory = categoryRepository.save(
                new Category("이미지 대분류 " + suffix, null)
        );

        Category childCategory = categoryRepository.save(
                new Category("이미지 세부 카테고리 " + suffix, rootCategory)
        );

        Product product = productRepository.save(
                new Product(
                        childCategory,
                        "이미지 테스트 상품",
                        "테스트 브랜드",
                        null,
                        BigDecimal.valueOf(20000),
                        3
                )
        );

        ProductImage productImage = productImageRepository.save(
                new ProductImage(
                        product,
                        "products/test/lazy-" + suffix + ".webp",
                        1
                )
        );

        entityManager.flush();
        Long productImageId = productImage.getId();
        entityManager.clear();

        ProductImage foundImage =
                productImageRepository.findById(productImageId)
                        .orElseThrow();

        assertThat(Hibernate.isInitialized(foundImage.getProduct()))
                .isFalse();

        assertThat(foundImage.getProduct().getName())
                .isEqualTo("이미지 테스트 상품");

        assertThat(Hibernate.isInitialized(foundImage.getProduct()))
                .isTrue();
    }
}
