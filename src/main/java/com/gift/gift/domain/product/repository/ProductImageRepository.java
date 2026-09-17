package com.gift.gift.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.product.entity.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
}
