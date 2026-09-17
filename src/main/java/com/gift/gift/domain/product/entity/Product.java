package com.gift.gift.domain.product.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.gift.gift.global.common.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 255)
    private String brand;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Integer views = 0;

    @Column(nullable = false)
    private Integer sales = 0;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Product(
            Category category,
            String name,
            String brand,
            String description,
            BigDecimal price,
            Integer quantity
    ) {
        validateCategory(category);
        validateName(name);
        validateBrand(brand);
        validatePrice(price);
        validateQuantity(quantity);

        this.category = category;
        this.name = name.trim();
        this.brand = brand.trim();
        this.description = description;
        this.price = price;
        this.quantity = quantity;
    }

    public boolean isSoldOut() {
        return quantity == 0;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static void validateCategory(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("상품 카테고리는 필수입니다.");
        }

        if (category.isRoot()) {
            throw new IllegalArgumentException("상품은 세부 카테고리에만 등록할 수 있습니다.");
        }

        if (category.isDeleted()) {
            throw new IllegalArgumentException("삭제된 카테고리에는 상품을 등록할 수 없습니다.");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("상품명은 필수입니다.");
        }

        if (name.trim().length() > 255) {
            throw new IllegalArgumentException("상품명은 255자를 넘을 수 없습니다.");
        }
    }

    private static void validateBrand(String brand) {
        if (brand == null || brand.isBlank()) {
            throw new IllegalArgumentException("브랜드명은 필수입니다.");
        }

        if (brand.trim().length() > 255) {
            throw new IllegalArgumentException("브랜드명은 255자를 넘을 수 없습니다.");
        }
    }

    private static void validatePrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("상품 가격은 0 이상이어야 합니다.");
        }
    }

    private static void validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 0) {
            throw new IllegalArgumentException("상품 재고는 0 이상이어야 합니다.");
        }
    }
}
