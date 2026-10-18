package com.gift.gift.domain.product.service;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;
import com.gift.gift.domain.product.support.ImageUrlProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringBootTest(properties = {
        "spring.jpa.open-in-view=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "storage.s3.region=ap-northeast-2",
        "storage.s3.bucket=product-detail-test"
})
class ProductDetailSingleQueryIntegrationTest {

    @Autowired private ProductQueryService service;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private ImageUrlProvider imageUrlProvider;

    @Test
    @DisplayName("상세는 트랜잭션 없이 한 문장으로 읽고 이미지 URL을 구성한다")
    void readsDetailWithOneStatementWithoutTransaction() {
        var transaction = new TransactionTemplate(transactionManager);
        String key = "single-query/" + UUID.randomUUID();
        Long[] ids = transaction.execute(status -> {
            Category root = new Category(key, null);
            entityManager.persist(root);
            Category category = new Category(key + "-child", root);
            entityManager.persist(category);
            Product product = new Product(category, "상품", "브랜드", null, BigDecimal.ZERO, 0);
            entityManager.persist(product);
            entityManager.persist(new ProductImage(product, key, null));
            return new Long[]{product.getId(), category.getId(), root.getId()};
        });
        try {
            when(imageUrlProvider.generateUrl(key)).thenAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                return "https://image.test/single";
            });
            var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
            statistics.clear();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            var response = service.getProductDetail(ids[0]).product();

            assertThat(response.productId()).isEqualTo(ids[0]);
            assertThat(response.stockQuantity()).isZero();
            assertThat(response.description()).isNull();
            assertThat(response.images()).singleElement().satisfies(image -> {
                assertThat(image.imageUrl()).isEqualTo("https://image.test/single");
                assertThat(image.displayOrder()).isNull();
            });
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
            assertThat(statistics.getTransactionCount()).isZero();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        } finally {
            transaction.executeWithoutResult(status -> {
                entityManager.createQuery("DELETE FROM ProductImage i WHERE i.product.id = :id")
                        .setParameter("id", ids[0]).executeUpdate();
                entityManager.remove(entityManager.find(Product.class, ids[0]));
                entityManager.flush();
                entityManager.remove(entityManager.find(Category.class, ids[1]));
                entityManager.flush();
                entityManager.remove(entityManager.find(Category.class, ids[2]));
            });
        }
    }
}
