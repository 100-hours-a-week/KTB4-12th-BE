package com.gift.gift.domain.product.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(
        name = "product_images",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_product_images_product_sort_order",
                        columnNames = {"product_id", "sort_order"}
                ),
                @UniqueConstraint(
                        name = "uk_product_images_object_key",
                        columnNames = "object_key"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "object_key", nullable = false, length = 255)
    private String objectKey;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public ProductImage(
            Product product,
            String objectKey,
            Integer sortOrder
    ) {
        validateProduct(product);
        validateObjectKey(objectKey);

        this.product = product;
        this.objectKey = objectKey;
        this.sortOrder = sortOrder;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static void validateProduct(Product product) {
        if (product == null) {
            throw new IllegalArgumentException("상품 이미지는 상품에 속해야 합니다.");
        }

        if (product.isDeleted()) {
            throw new IllegalArgumentException("삭제된 상품에는 이미지를 등록할 수 없습니다.");
        }
    }

    private static void validateObjectKey(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("이미지 Object Key는 필수입니다.");
        }

        if (objectKey.length() > 255) {
            throw new IllegalArgumentException(
                    "이미지 Object Key는 255자를 넘을 수 없습니다."
            );
        }
    }
}
